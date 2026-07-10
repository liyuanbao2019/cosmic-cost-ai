package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.DeepSplitJobResponse;
import com.example.cosmiccostai.dto.DeepSplitRequest;
import com.example.cosmiccostai.dto.DeepSplitSuggestion;
import com.example.cosmiccostai.model.FunctionSplitRow;
import com.example.cosmiccostai.model.ProjectMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DeepSplitAiService {

    private static final Logger log = LoggerFactory.getLogger(DeepSplitAiService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> OPERATIONS = new HashSet<String>();
    private static final int MAX_REQUEST_ROWS = 2000;
    private static final int EVIDENCE_BLOCK_CHARS = 900;
    private static final int MAX_EVIDENCE_BLOCKS = 10;
    private static final int MAX_GROUPS_PER_CHUNK = 6;
    private static final int STATE_SAVE_RETRIES = 6;
    private static final long STATE_SAVE_RETRY_MILLIS = 250L;

    static {
        OPERATIONS.add("SPLIT_FUNCTION");
        OPERATIONS.add("ADD_MISSING_MOVEMENT");
        OPERATIONS.add("MERGE_DUPLICATE");
    }

    private final FileStorageService fileStorageService;
    private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final long chunkTimeoutSeconds;
    private final int maxRetries;
    private final int chunkPromptChars;
    private final int evidenceChars;

    @Autowired
    public DeepSplitAiService(FileStorageService fileStorageService,
                              @Qualifier("deepSplitExecutor") TaskExecutor taskExecutor,
                              @Value("${cosmic.ai.base-url:}") String baseUrl,
                              @Value("${cosmic.ai.api-key:}") String apiKey,
                              @Value("${cosmic.ai.model:gpt-4o-mini}") String model,
                              @Value("${cosmic.ai.deep-split.chunk-timeout-seconds:180}") long chunkTimeoutSeconds,
                              @Value("${cosmic.ai.max-retries:2}") int maxRetries,
                              @Value("${cosmic.ai.deep-split.chunk-prompt-chars:9000}") int chunkPromptChars,
                              @Value("${cosmic.ai.deep-split.evidence-chars:5000}") int evidenceChars) {
        this.fileStorageService = fileStorageService;
        this.taskExecutor = taskExecutor;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.chunkTimeoutSeconds = Math.max(20, chunkTimeoutSeconds);
        this.maxRetries = Math.max(0, maxRetries);
        this.chunkPromptChars = Math.max(6000, chunkPromptChars);
        this.evidenceChars = Math.max(2000, evidenceChars);
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public DeepSplitJobResponse start(String projectId, DeepSplitRequest request) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        List<FunctionSplitRow> rows = request == null ? null : request.getRows();
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException("请先生成或导入功能点拆分数据，再执行 AI 深度审校。");
        }
        if (rows.size() > MAX_REQUEST_ROWS) {
            throw new BusinessException("单个深度审校任务最多支持 " + MAX_REQUEST_ROWS + " 行，请按项目或模块拆分后处理。");
        }
        if (!StringUtils.hasText(resolveApiKey())) {
            throw new BusinessException("AI api key is not configured. Please set OPENAI_API_KEY.");
        }

        String jobId = "DS" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        DeepSplitJobState state = new DeepSplitJobState();
        state.setJobId(jobId);
        state.setProjectId(projectId);
        state.setStatus("QUEUED");
        state.setModel(model);
        state.setOriginalRows(copyRows(rows));
        state.setChunks(buildChunks(rows));
        state.setCreatedAt(now());
        state.setUpdatedAt(state.getCreatedAt());

        Path sourcePath = resolveRequirementSource(metadata);
        state.setSourceAvailable(sourcePath != null);
        state.setSourceType(sourcePath == null ? "EXCEL_ROWS" : "REQUIREMENT_DOCUMENT");
        if (sourcePath == null) {
            state.getWarnings().add("当前项目没有可用的原始需求文本：AI 只会依据 Excel/审核表已有内容纠正结构，不会凭空补充新的业务功能。");
        } else {
            state.getWarnings().add("长需求文本将按功能过程检索相关证据并分批处理，不会一次性发送全文。");
        }
        if (state.getChunks().size() > 1) {
            state.getWarnings().add("本次任务已拆分为 " + state.getChunks().size() + " 批；单批超时不会影响其他批次，失败批次可单独重试。");
        }
        saveState(state);
        submit(state.getProjectId(), state.getJobId());
        return toResponse(state);
    }

    public DeepSplitJobResponse get(String projectId, String jobId) {
        fileStorageService.loadMetadata(projectId);
        return toResponse(loadState(projectId, jobId));
    }

    public DeepSplitJobResponse retryFailed(String projectId, String jobId) {
        DeepSplitJobState state = loadState(projectId, jobId);
        if ("RUNNING".equals(state.getStatus()) || "QUEUED".equals(state.getStatus())) {
            throw new BusinessException("深度审校任务仍在执行中，无需重复提交。");
        }
        int reset = 0;
        List<DeepSplitChunk> retryChunks = new ArrayList<DeepSplitChunk>();
        for (DeepSplitChunk chunk : state.getChunks()) {
            if ("FAILED".equals(chunk.getStatus())) {
                List<DeepSplitChunk> smallerChunks = splitFailedChunk(chunk, state.getOriginalRows());
                retryChunks.addAll(smallerChunks);
                reset += smallerChunks.size();
            } else {
                retryChunks.add(chunk);
            }
        }
        if (reset == 0) {
            throw new BusinessException("当前任务没有可重试的失败批次。");
        }
        state.setChunks(retryChunks);
        state.setStatus("QUEUED");
        state.setUpdatedAt(now());
        state.getWarnings().add("已将失败批次自动拆小后重新提交 " + reset + " 个批次，已完成建议将被保留。");
        saveState(state);
        submit(projectId, jobId);
        return toResponse(state);
    }

    private void submit(final String projectId, final String jobId) {
        try {
            taskExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    runJob(projectId, jobId);
                }
            });
        } catch (RuntimeException ex) {
            DeepSplitJobState state = loadState(projectId, jobId);
            state.setStatus("FAILED");
            state.getWarnings().add("任务队列已满，请稍后重试：" + rootMessage(ex));
            state.setUpdatedAt(now());
            saveState(state);
            throw new BusinessException("AI 深度审校任务队列已满，请稍后重试。", ex);
        }
    }

    private void runJob(String projectId, String jobId) {
        DeepSplitJobState state;
        try {
            state = loadState(projectId, jobId);
            state.setStatus("RUNNING");
            state.setUpdatedAt(now());
            saveState(state);
        } catch (RuntimeException ex) {
            log.error("Start deep split job failed, projectId={}, jobId={}", projectId, jobId, ex);
            return;
        }

        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        List<EvidenceBlock> evidenceBlocks = readEvidenceBlocks(resolveRequirementSource(metadata));
        for (DeepSplitChunk chunk : state.getChunks()) {
            if (!"PENDING".equals(chunk.getStatus())) {
                continue;
            }
            try {
                chunk.setStatus("RUNNING");
                chunk.setAttempts(chunk.getAttempts() + 1);
                state.setUpdatedAt(now());
                saveState(state);

                List<EvidenceBlock> selectedEvidence = selectEvidenceBlocks(
                        evidenceBlocks, state.getOriginalRows(), chunk.getRowNumbers());
                String prompt = buildPrompt(state, chunk, selectedEvidence);
                String aiText = callAiWithRetries(projectId, jobId, chunk, prompt, selectedEvidence.size());
                List<DeepSplitSuggestion> suggestions = parseSuggestions(aiText);
                List<DeepSplitSuggestion> accepted = normalizeSuggestions(state, chunk, selectedEvidence, suggestions);
                mergeSuggestions(state.getSuggestions(), accepted);
                chunk.setSuggestionCount(accepted.size());
                chunk.setStatus("COMPLETED");
                chunk.setError("");
            } catch (RuntimeException ex) {
                chunk.setStatus("FAILED");
                chunk.setError(userFacingChunkError(ex));
                log.warn("Deep split chunk failed, projectId={}, jobId={}, chunkId={}",
                        projectId, jobId, chunk.getId(), ex);
            }
            state.setUpdatedAt(now());
            saveState(state);
        }
        finishState(state);
        saveState(state);
    }

    List<DeepSplitChunk> buildChunks(List<FunctionSplitRow> rows) {
        Map<String, List<Integer>> groups = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < rows.size(); i++) {
            FunctionSplitRow row = rows.get(i);
            String key = text(row.getLevel1Module()) + "\u001f" + text(row.getLevel2Module()) + "\u001f"
                    + text(row.getLevel3Module()) + "\u001f" + text(row.getFunctionProcess());
            List<Integer> numbers = groups.get(key);
            if (numbers == null) {
                numbers = new ArrayList<Integer>();
                groups.put(key, numbers);
            }
            numbers.add(i + 1);
        }

        List<Map.Entry<String, List<Integer>>> orderedGroups = new ArrayList<Map.Entry<String, List<Integer>>>(groups.entrySet());
        Collections.sort(orderedGroups, new Comparator<Map.Entry<String, List<Integer>>>() {
            @Override
            public int compare(Map.Entry<String, List<Integer>> left, Map.Entry<String, List<Integer>> right) {
                int processCompare = groupProcess(left.getKey()).compareTo(groupProcess(right.getKey()));
                return processCompare != 0 ? processCompare : left.getKey().compareTo(right.getKey());
            }
        });

        List<DeepSplitChunk> chunks = new ArrayList<DeepSplitChunk>();
        DeepSplitChunk current = new DeepSplitChunk();
        int estimatedChars = 0;
        int groupCount = 0;
        for (Map.Entry<String, List<Integer>> entry : orderedGroups) {
            List<Integer> group = entry.getValue();
            int groupChars = estimateRowsChars(rows, group);
            if (!current.getRowNumbers().isEmpty()
                    && (estimatedChars + groupChars > chunkPromptChars || groupCount >= MAX_GROUPS_PER_CHUNK)) {
                chunks.add(current);
                current = new DeepSplitChunk();
                estimatedChars = 0;
                groupCount = 0;
            }
            current.getRowNumbers().addAll(group);
            estimatedChars += groupChars;
            groupCount++;
        }
        if (!current.getRowNumbers().isEmpty()) {
            chunks.add(current);
        }
        for (int i = 0; i < chunks.size(); i++) {
            DeepSplitChunk chunk = chunks.get(i);
            chunk.setId(String.format(Locale.ROOT, "C%03d", i + 1));
            chunk.setStatus("PENDING");
        }
        return chunks;
    }

    private List<DeepSplitChunk> splitFailedChunk(DeepSplitChunk failed, List<FunctionSplitRow> rows) {
        Map<String, List<Integer>> groups = new LinkedHashMap<String, List<Integer>>();
        for (Integer number : failed.getRowNumbers()) {
            if (number == null || number < 1 || number > rows.size()) {
                continue;
            }
            FunctionSplitRow row = rows.get(number - 1);
            String key = text(row.getLevel1Module()) + "\u001f" + text(row.getLevel2Module()) + "\u001f"
                    + text(row.getLevel3Module()) + "\u001f" + text(row.getFunctionProcess());
            List<Integer> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<Integer>();
                groups.put(key, group);
            }
            group.add(number);
        }

        List<DeepSplitChunk> result = new ArrayList<DeepSplitChunk>();
        DeepSplitChunk current = new DeepSplitChunk();
        int retryPromptBudget = Math.max(4000, chunkPromptChars / 2);
        int estimatedChars = 0;
        int groupCount = 0;
        int part = 1;
        for (List<Integer> group : groups.values()) {
            int groupChars = estimateRowsChars(rows, group);
            if (!current.getRowNumbers().isEmpty()
                    && (estimatedChars + groupChars > retryPromptBudget || groupCount >= 3)) {
                current.setId(failed.getId() + "-R" + part++);
                current.setStatus("PENDING");
                result.add(current);
                current = new DeepSplitChunk();
                estimatedChars = 0;
                groupCount = 0;
            }
            current.getRowNumbers().addAll(group);
            estimatedChars += groupChars;
            groupCount++;
        }
        if (!current.getRowNumbers().isEmpty()) {
            current.setId(failed.getId() + "-R" + part);
            current.setStatus("PENDING");
            result.add(current);
        }
        if (result.isEmpty()) {
            failed.setStatus("PENDING");
            failed.setError("");
            result.add(failed);
        }
        return result;
    }

    private String groupProcess(String key) {
        int separator = key == null ? -1 : key.lastIndexOf('\u001f');
        return separator >= 0 ? key.substring(separator + 1) : text(key);
    }

    private int estimateRowsChars(List<FunctionSplitRow> rows, List<Integer> rowNumbers) {
        int count = 500;
        for (Integer number : rowNumbers) {
            if (number == null || number < 1 || number > rows.size()) {
                continue;
            }
            FunctionSplitRow row = rows.get(number - 1);
            count += 220 + text(row.getFunctionDescription()).length()
                    + text(row.getSubProcessDescription()).length()
                    + text(row.getDataAttributes()).length();
        }
        return count;
    }

    private String buildPrompt(DeepSplitJobState state, DeepSplitChunk chunk, List<EvidenceBlock> evidence) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是资深 COSMIC 功能点分析专家。请对当前批次的完整功能过程进行结构化二次审校。\n");
        builder.append("只输出确有必要且可以直接采纳的变更建议；正确内容不要输出建议。\n");
        builder.append("允许的 operation 只有 SPLIT_FUNCTION、ADD_MISSING_MOVEMENT、MERGE_DUPLICATE。\n");
        builder.append("- SPLIT_FUNCTION：一个功能过程包含多个独立触发事件或业务结果，需要拆为多个功能过程。\n");
        builder.append("- ADD_MISSING_MOVEMENT：当前功能过程遗漏了有明确依据的 E/R/W/X 数据移动。\n");
        builder.append("- MERGE_DUPLICATE：多个功能过程的触发、业务对象和数据移动实质重复，应合并或按复用处理。\n");
        builder.append("重要约束：\n");
        builder.append("1. 不得为了增加 CFP 凭空创造功能、数据组或属性。\n");
        builder.append("2. replacementRows 必须给出采纳建议后的完整替换行，而不是只给变化字段。\n");
        builder.append("3. targetRowNumbers 必须使用输入中的 rowNumber，采用 1 开始的原表行号。\n");
        builder.append("4. 每个 replacementRows 元素必须包含 projectName,level1Module,level2Module,level3Module,functionalUser,triggerEvent,functionProcess,functionDescription,subProcessDescription,movementType,dataGroup,dataAttributes,sequenceDiagram,reuseDegree,cfp。\n");
        builder.append("5. 每个功能过程必须由 E 开始，并以 W 或 X 形成业务结果；R/W 只用于持久化数据组，X 必须跨软件边界输出。\n");
        builder.append("6. sourceReferences 只能填写下方实际提供的 REQ-Bxxxx 或 EXCEL-ROW-x 编号；evidence 简要写明依据。\n");
        builder.append("7. 如果证据不足，宁可不输出建议，不要猜测。\n");
        if (!state.isSourceAvailable()) {
            builder.append("8. 当前没有原始需求文档。不得新增表中没有体现的业务功能；只能依据 EXCEL-ROW 现有字段修正功能边界、重复项或明显缺失的数据移动。\n");
        }
        builder.append("输出严格 JSON 数组，不要 Markdown。元素格式：\n");
        builder.append("{\"operation\":\"SPLIT_FUNCTION\",\"title\":\"\",\"reason\":\"\",\"evidence\":\"\",\"sourceReferences\":[\"REQ-B0001\"],\"confidence\":0.0,\"targetRowNumbers\":[1],\"replacementRows\":[{...}]}\n\n");
        builder.append("【当前拆分行】\n");
        for (Integer number : chunk.getRowNumbers()) {
            if (number == null || number < 1 || number > state.getOriginalRows().size()) {
                continue;
            }
            builder.append("EXCEL-ROW-").append(number).append(" ");
            builder.append(toJson(new IndexedRow(number, state.getOriginalRows().get(number - 1)))).append("\n");
        }
        builder.append("\n【检索到的原始需求证据】\n");
        if (evidence.isEmpty()) {
            builder.append("无原始需求文本。\n");
        } else {
            for (EvidenceBlock block : evidence) {
                builder.append("[").append(block.getId()).append("] ").append(block.getText()).append("\n");
            }
        }
        return builder.toString();
    }

    private String callAiWithRetries(String projectId, String jobId, DeepSplitChunk chunk,
                                     String prompt, int evidenceBlockCount) {
        RuntimeException last = null;
        int attempts = maxRetries + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long startedAt = System.nanoTime();
            String context = "projectId=" + projectId + ", jobId=" + jobId + ", chunkId=" + chunk.getId()
                    + ", attempt=" + attempt + "/" + attempts + ", rows=" + chunk.getRowNumbers().size()
                    + ", evidenceBlocks=" + evidenceBlockCount + ", promptChars=" + prompt.length()
                    + ", model=" + model + ", timeoutSeconds=" + chunkTimeoutSeconds;
            log.info("Deep split AI call started: {}", context);
            appendCallLog(projectId, jobId, "START " + context);
            try {
                ChatLanguageModel chatModel = OpenAiChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(resolveApiKey())
                        .modelName(model)
                        .temperature(0.0)
                        .timeout(Duration.ofSeconds(chunkTimeoutSeconds))
                        .maxRetries(0)
                        .build();
                String response = chatModel.generate(prompt);
                long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                String success = context + ", elapsedMs=" + elapsedMillis + ", responseChars=" + text(response).length();
                log.info("Deep split AI call succeeded: {}", success);
                appendCallLog(projectId, jobId, "SUCCESS " + success);
                return response;
            } catch (RuntimeException ex) {
                last = ex;
                long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                String failure = context + ", elapsedMs=" + elapsedMillis + ", error=" + rootMessage(ex);
                log.warn("Deep split AI call failed: {}", failure);
                appendCallLog(projectId, jobId, "FAILED " + failure);
                if (attempt < attempts) {
                    waitBeforeRetry(attempt);
                }
            }
        }
        throw last == null ? new BusinessException("AI 深度审校调用失败。") : last;
    }

    private void waitBeforeRetry(int completedAttempt) {
        try {
            Thread.sleep(Math.min(6000L, 1500L * completedAttempt));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("AI 深度审校重试已中断。", ex);
        }
    }

    private void appendCallLog(String projectId, String jobId, String message) {
        try {
            Path logPath = jobDir(projectId, jobId).resolve("ai-call.log");
            String line = now() + " | " + message + System.lineSeparator();
            Files.write(logPath, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.warn("Write deep split AI call log failed, projectId={}, jobId={}: {}",
                    projectId, jobId, ex.getMessage());
        }
    }

    private List<DeepSplitSuggestion> parseSuggestions(String aiText) {
        String json = stripJsonArray(aiText);
        try {
            return objectMapper.readValue(json, new TypeReference<List<DeepSplitSuggestion>>() {
            });
        } catch (IOException ex) {
            throw new BusinessException("AI 深度审校结果不是有效 JSON，请重试当前批次。", ex);
        }
    }

    private List<DeepSplitSuggestion> normalizeSuggestions(DeepSplitJobState state,
                                                           DeepSplitChunk chunk,
                                                           List<EvidenceBlock> evidence,
                                                           List<DeepSplitSuggestion> rawSuggestions) {
        List<DeepSplitSuggestion> result = new ArrayList<DeepSplitSuggestion>();
        Set<Integer> chunkRows = new HashSet<Integer>(chunk.getRowNumbers());
        Set<String> validReferences = new HashSet<String>();
        for (Integer number : chunk.getRowNumbers()) {
            validReferences.add("EXCEL-ROW-" + number);
        }
        for (EvidenceBlock block : evidence) {
            validReferences.add(block.getId());
        }
        int index = 0;
        for (DeepSplitSuggestion suggestion : rawSuggestions == null
                ? Collections.<DeepSplitSuggestion>emptyList() : rawSuggestions) {
            if (suggestion == null || !OPERATIONS.contains(text(suggestion.getOperation()).toUpperCase(Locale.ROOT))) {
                continue;
            }
            suggestion.setOperation(text(suggestion.getOperation()).toUpperCase(Locale.ROOT));
            List<Integer> targets = normalizeTargetRows(suggestion.getTargetRowNumbers(), chunkRows, state.getOriginalRows().size());
            if (targets.isEmpty()) {
                continue;
            }
            List<String> references = normalizeReferences(suggestion.getSourceReferences(), validReferences);
            if (references.isEmpty() || !StringUtils.hasText(suggestion.getEvidence()) || !StringUtils.hasText(suggestion.getReason())) {
                continue;
            }
            FunctionSplitRow base = state.getOriginalRows().get(targets.get(0) - 1);
            List<FunctionSplitRow> replacements = normalizeReplacementRows(suggestion.getReplacementRows(), base);
            if (replacements.isEmpty()) {
                continue;
            }
            if ("SPLIT_FUNCTION".equals(suggestion.getOperation()) && distinctProcesses(replacements) < 2) {
                continue;
            }
            if ("ADD_MISSING_MOVEMENT".equals(suggestion.getOperation()) && replacements.size() <= targets.size()) {
                continue;
            }
            if ("MERGE_DUPLICATE".equals(suggestion.getOperation()) && targets.size() < 2) {
                continue;
            }
            suggestion.setTargetRowNumbers(targets);
            suggestion.setSourceReferences(references);
            suggestion.setReplacementRows(replacements);
            suggestion.setConfidence(Math.max(0, Math.min(1, suggestion.getConfidence())));
            suggestion.setCfpBefore(cfpForTargets(state.getOriginalRows(), targets));
            suggestion.setCfpAfter(cfpForRows(replacements));
            suggestion.setId(state.getJobId() + "-" + chunk.getId() + "-S" + (++index));
            if (!StringUtils.hasText(suggestion.getTitle())) {
                suggestion.setTitle(defaultTitle(suggestion.getOperation(), base));
            }
            result.add(suggestion);
        }
        return result;
    }

    private List<Integer> normalizeTargetRows(List<Integer> values, Set<Integer> allowed, int rowCount) {
        Set<Integer> unique = new LinkedHashSet<Integer>();
        if (values != null) {
            for (Integer value : values) {
                if (value != null && value >= 1 && value <= rowCount && allowed.contains(value)) {
                    unique.add(value);
                }
            }
        }
        List<Integer> result = new ArrayList<Integer>(unique);
        Collections.sort(result);
        return result;
    }

    private List<String> normalizeReferences(List<String> values, Set<String> allowed) {
        List<String> result = new ArrayList<String>();
        if (values != null) {
            for (String value : values) {
                String reference = text(value).toUpperCase(Locale.ROOT);
                if (allowed.contains(reference) && !result.contains(reference)) {
                    result.add(reference);
                }
            }
        }
        return result;
    }

    private List<FunctionSplitRow> normalizeReplacementRows(List<FunctionSplitRow> values, FunctionSplitRow base) {
        List<FunctionSplitRow> result = new ArrayList<FunctionSplitRow>();
        if (values == null) {
            return result;
        }
        for (FunctionSplitRow source : values) {
            if (source == null || !validMovement(source.getMovementType())
                    || !StringUtils.hasText(source.getFunctionProcess())
                    || !StringUtils.hasText(source.getSubProcessDescription())
                    || !StringUtils.hasText(source.getDataGroup())
                    || !StringUtils.hasText(source.getDataAttributes())) {
                return Collections.emptyList();
            }
            FunctionSplitRow row = new FunctionSplitRow();
            row.setProjectName(firstText(source.getProjectName(), base.getProjectName()));
            row.setLevel1Module(firstText(source.getLevel1Module(), base.getLevel1Module()));
            row.setLevel2Module(firstText(source.getLevel2Module(), base.getLevel2Module(), row.getLevel1Module()));
            row.setLevel3Module(firstText(source.getLevel3Module(), base.getLevel3Module(), row.getLevel2Module()));
            row.setFunctionalUser(firstText(source.getFunctionalUser(), base.getFunctionalUser()));
            row.setTriggerEvent(firstText(source.getTriggerEvent(), source.getFunctionProcess(), base.getTriggerEvent()));
            row.setFunctionProcess(text(source.getFunctionProcess()));
            row.setFunctionDescription(firstText(source.getFunctionDescription(), base.getFunctionDescription()));
            row.setSubProcessDescription(text(source.getSubProcessDescription()));
            row.setMovementType(text(source.getMovementType()).toUpperCase(Locale.ROOT));
            row.setDataGroup(text(source.getDataGroup()));
            row.setDataAttributes(text(source.getDataAttributes()));
            row.setSequenceDiagram(firstText(source.getSequenceDiagram(), defaultSequence(row.getMovementType())));
            row.setReuseDegree(normalizeReuse(firstText(source.getReuseDegree(), base.getReuseDegree(), "新增")));
            row.setCfp(cfpByReuse(row.getReuseDegree()));
            result.add(row);
        }
        return result;
    }

    private int distinctProcesses(List<FunctionSplitRow> rows) {
        Set<String> values = new HashSet<String>();
        for (FunctionSplitRow row : rows) {
            values.add(text(row.getFunctionProcess()));
        }
        return values.size();
    }

    private void mergeSuggestions(List<DeepSplitSuggestion> existing, List<DeepSplitSuggestion> additions) {
        Set<String> keys = new HashSet<String>();
        for (DeepSplitSuggestion suggestion : existing) {
            keys.add(suggestionKey(suggestion));
        }
        for (DeepSplitSuggestion suggestion : additions) {
            if (keys.add(suggestionKey(suggestion))) {
                existing.add(suggestion);
            }
        }
    }

    private String suggestionKey(DeepSplitSuggestion suggestion) {
        return suggestion.getOperation() + "|" + suggestion.getTargetRowNumbers();
    }

    List<EvidenceBlock> selectEvidenceBlocks(List<EvidenceBlock> blocks,
                                             List<FunctionSplitRow> rows,
                                             List<Integer> rowNumbers) {
        if (blocks == null || blocks.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder query = new StringBuilder();
        for (Integer number : rowNumbers) {
            if (number == null || number < 1 || number > rows.size()) {
                continue;
            }
            FunctionSplitRow row = rows.get(number - 1);
            query.append(' ').append(text(row.getLevel1Module()))
                    .append(' ').append(text(row.getLevel2Module()))
                    .append(' ').append(text(row.getLevel3Module()))
                    .append(' ').append(text(row.getFunctionProcess()))
                    .append(' ').append(text(row.getSubProcessDescription()))
                    .append(' ').append(text(row.getDataGroup()));
        }
        final Set<String> queryGrams = grams(query.toString());
        List<EvidenceBlock> ranked = new ArrayList<EvidenceBlock>();
        for (EvidenceBlock block : blocks) {
            EvidenceBlock copy = new EvidenceBlock(block.getId(), block.getText(), block.getOrder());
            copy.setScore(overlap(queryGrams, grams(block.getText())));
            ranked.add(copy);
        }
        Collections.sort(ranked, new Comparator<EvidenceBlock>() {
            @Override
            public int compare(EvidenceBlock left, EvidenceBlock right) {
                int score = Integer.compare(right.getScore(), left.getScore());
                return score != 0 ? score : Integer.compare(left.getOrder(), right.getOrder());
            }
        });
        List<EvidenceBlock> selected = new ArrayList<EvidenceBlock>();
        int chars = 0;
        for (EvidenceBlock block : ranked) {
            if (selected.size() >= MAX_EVIDENCE_BLOCKS || chars + block.getText().length() > evidenceChars) {
                continue;
            }
            if (block.getScore() <= 0 && !selected.isEmpty()) {
                continue;
            }
            selected.add(block);
            chars += block.getText().length();
        }
        if (selected.isEmpty()) {
            selected.add(ranked.get(0));
        }
        Collections.sort(selected, new Comparator<EvidenceBlock>() {
            @Override
            public int compare(EvidenceBlock left, EvidenceBlock right) {
                return Integer.compare(left.getOrder(), right.getOrder());
            }
        });
        return selected;
    }

    private List<EvidenceBlock> readEvidenceBlocks(Path sourcePath) {
        if (sourcePath == null || !Files.exists(sourcePath)) {
            return Collections.emptyList();
        }
        try {
            List<EvidenceBlock> blocks = new ArrayList<EvidenceBlock>();
            StringBuilder current = new StringBuilder();
            try (BufferedReader reader = Files.newBufferedReader(sourcePath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = text(line);
                    if (!StringUtils.hasText(value)) {
                        flushEvidenceBlock(blocks, current);
                        continue;
                    }
                    if (current.length() > 0 && current.length() + value.length() + 1 > EVIDENCE_BLOCK_CHARS) {
                        flushEvidenceBlock(blocks, current);
                    }
                    if (value.length() > EVIDENCE_BLOCK_CHARS) {
                        int offset = 0;
                        while (offset < value.length()) {
                            int end = Math.min(value.length(), offset + EVIDENCE_BLOCK_CHARS);
                            if (current.length() > 0) {
                                flushEvidenceBlock(blocks, current);
                            }
                            blocks.add(new EvidenceBlock(blockId(blocks.size()), value.substring(offset, end), blocks.size()));
                            offset = end;
                        }
                    } else {
                        if (current.length() > 0) {
                            current.append('\n');
                        }
                        current.append(value);
                    }
                }
            }
            flushEvidenceBlock(blocks, current);
            return blocks;
        } catch (IOException ex) {
            log.warn("Read deep split evidence failed: {}", sourcePath, ex);
            return Collections.emptyList();
        }
    }

    private void flushEvidenceBlock(List<EvidenceBlock> blocks, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        blocks.add(new EvidenceBlock(blockId(blocks.size()), current.toString(), blocks.size()));
        current.setLength(0);
    }

    private String blockId(int zeroBasedIndex) {
        return String.format(Locale.ROOT, "REQ-B%04d", zeroBasedIndex + 1);
    }

    private Set<String> grams(String value) {
        String normalized = text(value).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        Set<String> result = new HashSet<String>();
        if (normalized.length() == 1) {
            result.add(normalized);
        }
        for (int i = 0; i + 1 < normalized.length(); i++) {
            result.add(normalized.substring(i, i + 2));
        }
        return result;
    }

    private int overlap(Set<String> left, Set<String> right) {
        int result = 0;
        for (String value : left) {
            if (right.contains(value)) {
                result++;
            }
        }
        return result;
    }

    private Path resolveRequirementSource(ProjectMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        if (StringUtils.hasText(metadata.getRequirementDetailPath())) {
            Path path = java.nio.file.Paths.get(metadata.getRequirementDetailPath());
            if (Files.exists(path)) {
                return path;
            }
        }
        if (StringUtils.hasText(metadata.getDocumentTextPath())) {
            Path path = java.nio.file.Paths.get(metadata.getDocumentTextPath());
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }

    private void finishState(DeepSplitJobState state) {
        int completed = 0;
        int failed = 0;
        for (DeepSplitChunk chunk : state.getChunks()) {
            if ("COMPLETED".equals(chunk.getStatus())) {
                completed++;
            } else if ("FAILED".equals(chunk.getStatus())) {
                failed++;
            }
        }
        if (failed == 0) {
            state.setStatus("COMPLETED");
        } else if (completed == 0) {
            state.setStatus("FAILED");
        } else {
            state.setStatus("PARTIAL");
            state.getWarnings().add("有 " + failed + " 个批次失败；可点击“重试失败批次”，无需重新处理已完成批次。");
        }
        state.setUpdatedAt(now());
    }

    private DeepSplitJobResponse toResponse(DeepSplitJobState state) {
        DeepSplitJobResponse response = new DeepSplitJobResponse();
        response.setJobId(state.getJobId());
        response.setProjectId(state.getProjectId());
        response.setStatus(state.getStatus());
        response.setModel(state.getModel());
        response.setSourceType(state.getSourceType());
        response.setSourceAvailable(state.isSourceAvailable());
        response.setTotalChunks(state.getChunks().size());
        int completed = 0;
        int failed = 0;
        for (DeepSplitChunk chunk : state.getChunks()) {
            if ("COMPLETED".equals(chunk.getStatus())) {
                completed++;
            } else if ("FAILED".equals(chunk.getStatus())) {
                failed++;
            }
        }
        response.setCompletedChunks(completed);
        response.setFailedChunks(failed);
        int finished = completed + failed;
        response.setProgressPercent(state.getChunks().isEmpty() ? 0
                : Math.min(100, (int) Math.round(finished * 100.0 / state.getChunks().size())));
        response.setCreatedAt(state.getCreatedAt());
        response.setUpdatedAt(state.getUpdatedAt());
        response.setWarnings(new ArrayList<String>(state.getWarnings()));
        response.setSuggestions(new ArrayList<DeepSplitSuggestion>(state.getSuggestions()));
        return response;
    }

    private Path jobDir(String projectId, String jobId) {
        if (!StringUtils.hasText(jobId) || !jobId.matches("DS[0-9A-Z]+")) {
            throw new BusinessException("无效的深度审校任务编号。");
        }
        return fileStorageService.resolveProjectDir(projectId).resolve("deep-split").resolve(jobId);
    }

    private void saveState(DeepSplitJobState state) {
        Path directory = jobDir(state.getProjectId(), state.getJobId());
        Path target = directory.resolve("state.json");
        Path temporary = directory.resolve("state.json.tmp." + UUID.randomUUID().toString());
        try {
            Files.createDirectories(directory);
            objectMapper.writeValue(temporary.toFile(), state);
            IOException lastFailure = null;
            for (int attempt = 1; attempt <= STATE_SAVE_RETRIES; attempt++) {
                try {
                    moveStateFile(temporary, target);
                    return;
                } catch (IOException ex) {
                    lastFailure = ex;
                    if (attempt < STATE_SAVE_RETRIES) {
                        waitBeforeStateSaveRetry();
                    }
                }
            }
            // Windows Defender, Explorer, or a live file viewer can briefly lock state.json.
            // Never discard an expensive AI result in that case: persist an immutable recovery
            // snapshot and let loadState select the newest valid snapshot on the next request.
            Path recovery = directory.resolve("state.json.recovery-" + System.currentTimeMillis()
                    + "-" + UUID.randomUUID().toString().substring(0, 8));
            Files.copy(temporary, recovery, StandardCopyOption.REPLACE_EXISTING);
            log.error("Deep split state target is locked; recovery snapshot saved, projectId={}, jobId={}, recovery={}",
                    state.getProjectId(), state.getJobId(), recovery, lastFailure);
        } catch (IOException ex) {
            throw new BusinessException("保存 AI 深度审校任务状态失败：" + state.getJobId(), ex);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The uniquely named temporary file is harmless and may still be held by Windows.
            }
        }
    }

    private void moveStateFile(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void waitBeforeStateSaveRetry() {
        try {
            Thread.sleep(STATE_SAVE_RETRY_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private DeepSplitJobState loadState(String projectId, String jobId) {
        Path directory = jobDir(projectId, jobId);
        List<Path> candidates = new ArrayList<Path>();
        Path primary = directory.resolve("state.json");
        if (Files.exists(primary)) {
            candidates.add(primary);
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "state.json.recovery-*")) {
            for (Path path : files) {
                candidates.add(path);
            }
        } catch (IOException ex) {
            throw new BusinessException("读取 AI 深度审校任务失败：" + jobId, ex);
        }
        Collections.sort(candidates, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                try {
                    return Files.getLastModifiedTime(right).compareTo(Files.getLastModifiedTime(left));
                } catch (IOException ex) {
                    return 0;
                }
            }
        });
        for (Path candidate : candidates) {
            try {
                return objectMapper.readValue(candidate.toFile(), DeepSplitJobState.class);
            } catch (IOException ex) {
                log.warn("Skip unreadable deep split state candidate, projectId={}, jobId={}, path={}",
                        projectId, jobId, candidate, ex);
            }
        }
        throw new BusinessException("AI 深度审校任务不存在或状态文件损坏：" + jobId);
    }

    private List<FunctionSplitRow> copyRows(List<FunctionSplitRow> rows) {
        return objectMapper.convertValue(rows, new TypeReference<List<FunctionSplitRow>>() {
        });
    }

    private String stripJsonArray(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        return start >= 0 && end >= start ? text.substring(start, end + 1) : text;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException ex) {
            throw new BusinessException("构造 AI 深度审校提示词失败。", ex);
        }
    }

    private double cfpForTargets(List<FunctionSplitRow> rows, List<Integer> numbers) {
        double total = 0;
        for (Integer number : numbers) {
            if (number != null && number >= 1 && number <= rows.size()) {
                total += cfpByReuse(normalizeReuse(rows.get(number - 1).getReuseDegree()));
            }
        }
        return total;
    }

    private double cfpForRows(List<FunctionSplitRow> rows) {
        double total = 0;
        for (FunctionSplitRow row : rows) {
            total += cfpByReuse(normalizeReuse(row.getReuseDegree()));
        }
        return total;
    }

    private double cfpByReuse(String reuseDegree) {
        if ("复用".equals(reuseDegree)) {
            return 1.0 / 3.0;
        }
        if ("利旧".equals(reuseDegree)) {
            return 0;
        }
        return 1;
    }

    private String normalizeReuse(String value) {
        if ("复用".equals(value) || "利旧".equals(value)) {
            return value;
        }
        return "新增";
    }

    private boolean validMovement(String value) {
        String movement = text(value).toUpperCase(Locale.ROOT);
        return "E".equals(movement) || "R".equals(movement) || "W".equals(movement) || "X".equals(movement);
    }

    private String defaultSequence(String movement) {
        if ("E".equals(movement)) {
            return "UI->后台服务";
        }
        if ("X".equals(movement)) {
            return "后台服务->UI";
        }
        return "后台服务->后台服务";
    }

    private String defaultTitle(String operation, FunctionSplitRow base) {
        String process = text(base.getFunctionProcess());
        if ("SPLIT_FUNCTION".equals(operation)) {
            return "拆分功能过程：" + process;
        }
        if ("MERGE_DUPLICATE".equals(operation)) {
            return "合并重复功能：" + process;
        }
        return "补充数据移动：" + process;
    }

    private String userFacingChunkError(Throwable throwable) {
        if (isTimeout(throwable)) {
            return "当前批次 AI 调用超时。已保留其他批次结果，可单独重试本批次。";
        }
        return "当前批次处理失败：" + rootMessage(throwable);
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedIOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private String resolveApiKey() {
        if (StringUtils.hasText(apiKey)) {
            return apiKey;
        }
        String value = System.getProperty("OPENAI_API_KEY");
        if (!StringUtils.hasText(value)) {
            value = System.getProperty("DEEPSEEK_API_KEY");
        }
        if (!StringUtils.hasText(value)) {
            value = System.getenv("OPENAI_API_KEY");
        }
        if (!StringUtils.hasText(value)) {
            value = System.getenv("DEEPSEEK_API_KEY");
        }
        return value;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String now() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }

    public static class DeepSplitJobState {
        private String jobId;
        private String projectId;
        private String status;
        private String model;
        private String sourceType;
        private boolean sourceAvailable;
        private String createdAt;
        private String updatedAt;
        private List<String> warnings = new ArrayList<String>();
        private List<FunctionSplitRow> originalRows = new ArrayList<FunctionSplitRow>();
        private List<DeepSplitChunk> chunks = new ArrayList<DeepSplitChunk>();
        private List<DeepSplitSuggestion> suggestions = new ArrayList<DeepSplitSuggestion>();

        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getSourceType() { return sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }
        public boolean isSourceAvailable() { return sourceAvailable; }
        public void setSourceAvailable(boolean sourceAvailable) { this.sourceAvailable = sourceAvailable; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
        public List<String> getWarnings() { return warnings; }
        public void setWarnings(List<String> warnings) { this.warnings = warnings; }
        public List<FunctionSplitRow> getOriginalRows() { return originalRows; }
        public void setOriginalRows(List<FunctionSplitRow> originalRows) { this.originalRows = originalRows; }
        public List<DeepSplitChunk> getChunks() { return chunks; }
        public void setChunks(List<DeepSplitChunk> chunks) { this.chunks = chunks; }
        public List<DeepSplitSuggestion> getSuggestions() { return suggestions; }
        public void setSuggestions(List<DeepSplitSuggestion> suggestions) { this.suggestions = suggestions; }
    }

    public static class DeepSplitChunk {
        private String id;
        private String status = "PENDING";
        private String error;
        private int attempts;
        private int suggestionCount;
        private List<Integer> rowNumbers = new ArrayList<Integer>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public int getAttempts() { return attempts; }
        public void setAttempts(int attempts) { this.attempts = attempts; }
        public int getSuggestionCount() { return suggestionCount; }
        public void setSuggestionCount(int suggestionCount) { this.suggestionCount = suggestionCount; }
        public List<Integer> getRowNumbers() { return rowNumbers; }
        public void setRowNumbers(List<Integer> rowNumbers) { this.rowNumbers = rowNumbers; }
    }

    static class EvidenceBlock {
        private String id;
        private String text;
        private int order;
        private int score;

        EvidenceBlock() {
        }

        EvidenceBlock(String id, String text, int order) {
            this.id = id;
            this.text = text;
            this.order = order;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public int getOrder() { return order; }
        public void setOrder(int order) { this.order = order; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
    }

    private static class IndexedRow {
        private final int rowNumber;
        private final FunctionSplitRow row;

        private IndexedRow(int rowNumber, FunctionSplitRow row) {
            this.rowNumber = rowNumber;
            this.row = row;
        }

        public int getRowNumber() { return rowNumber; }
        public FunctionSplitRow getRow() { return row; }
    }
}
