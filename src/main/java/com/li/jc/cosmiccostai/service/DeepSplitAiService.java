package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.DeepSplitJobResponse;
import com.li.jc.cosmiccostai.dto.DeepSplitRequest;
import com.li.jc.cosmiccostai.dto.DeepSplitSuggestion;
import com.li.jc.cosmiccostai.dto.AiModelProfile;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DeepSplitAiService {

    private static final Logger log = LoggerFactory.getLogger(DeepSplitAiService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> OPERATIONS = new HashSet<String>();
    private static final int MIN_REQUEST_ROWS_LIMIT = 2000;
    private static final double MIN_ACCEPTED_CONFIDENCE = 0.78;
    private static final int EVIDENCE_BLOCK_CHARS = 900;
    private static final int MAX_EVIDENCE_BLOCKS = 10;
    private static final int MAX_GROUPS_PER_CHUNK = 6;
    private static final int MAX_GLOBAL_DUPLICATE_CANDIDATE_ROWS = 24;
    private static final int STATE_SAVE_RETRIES = 6;
    private static final long STATE_SAVE_RETRY_MILLIS = 250L;

    static {
        OPERATIONS.add("SPLIT_FUNCTION");
        OPERATIONS.add("ADD_MISSING_MOVEMENT");
        OPERATIONS.add("REMOVE_UNSUPPORTED_MOVEMENT");
        OPERATIONS.add("ADJUST_MOVEMENT_STRUCTURE");
        OPERATIONS.add("ADJUST_REUSE_CLASSIFICATION");
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
    private final int maxRequestRows;
    private final int chunkParallelism;
    private final AiModelConfigService aiModelConfigService;
    private final RuleValidationService ruleValidationService;
    private final SplitRuleConfigService splitRuleConfigService;

    public DeepSplitAiService(FileStorageService fileStorageService, TaskExecutor taskExecutor,
                              String baseUrl, String apiKey, String model, long chunkTimeoutSeconds,
                              int maxRetries, int chunkPromptChars, int evidenceChars,
                              int maxRequestRows, int chunkParallelism) {
        this(fileStorageService, taskExecutor, baseUrl, apiKey, model, chunkTimeoutSeconds,
                maxRetries, chunkPromptChars, evidenceChars, maxRequestRows, chunkParallelism, null,
                new RuleValidationService(),
                new SplitRuleConfigService("target/deep-split-test-config/split-check-rules.md"));
    }

    @Autowired
    public DeepSplitAiService(FileStorageService fileStorageService,
                              @Qualifier("deepSplitExecutor") TaskExecutor taskExecutor,
                              @Value("${cosmic.ai.base-url:}") String baseUrl,
                              @Value("${cosmic.ai.api-key:}") String apiKey,
                              @Value("${cosmic.ai.model:gpt-4o-mini}") String model,
                              @Value("${cosmic.ai.deep-split.chunk-timeout-seconds:180}") long chunkTimeoutSeconds,
                              @Value("${cosmic.ai.deep-split.max-retries:${cosmic.ai.max-retries:1}}") int maxRetries,
                              @Value("${cosmic.ai.deep-split.chunk-prompt-chars:9000}") int chunkPromptChars,
                              @Value("${cosmic.ai.deep-split.evidence-chars:5000}") int evidenceChars,
                               @Value("${cosmic.ai.deep-split.max-request-rows:10000}") int maxRequestRows,
                               @Value("${cosmic.ai.deep-split.chunk-parallelism:4}") int chunkParallelism,
                               AiModelConfigService aiModelConfigService,
                               RuleValidationService ruleValidationService,
                               SplitRuleConfigService splitRuleConfigService) {
        this.fileStorageService = fileStorageService;
        this.taskExecutor = taskExecutor;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.chunkTimeoutSeconds = Math.max(20, chunkTimeoutSeconds);
        this.maxRetries = Math.max(0, maxRetries);
        this.chunkPromptChars = Math.max(6000, chunkPromptChars);
        this.evidenceChars = Math.max(2000, evidenceChars);
        this.maxRequestRows = Math.max(MIN_REQUEST_ROWS_LIMIT, maxRequestRows);
        this.chunkParallelism = Math.max(1, chunkParallelism);
        this.aiModelConfigService = aiModelConfigService;
        this.ruleValidationService = ruleValidationService;
        this.splitRuleConfigService = splitRuleConfigService;
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
        if (rows.size() > currentMaxRequestRows()) {
            throw new BusinessException("单个深度审校任务最多支持 " + currentMaxRequestRows()
                    + " 行，请按项目或模块拆分后处理，或调大 cosmic.ai.deep-split.max-request-rows。");
        }
        if (!hasConfiguredApiKey()) {
            throw new BusinessException("AI api key is not configured. Please set OPENAI_API_KEY.");
        }
        log.info("AI deep split start requested. projectId={}, rows={}, maxRequestRows={}, model={}, chunkPromptChars={}, evidenceChars={}, timeoutSeconds={}, chunkParallelism={}",
                projectId, rows.size(), currentMaxRequestRows(), currentModel(), currentChunkPromptChars(),
                currentEvidenceChars(), currentChunkTimeoutSeconds(), currentChunkParallelism());

        String jobId = "DS" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        DeepSplitJobState state = new DeepSplitJobState();
        state.setJobId(jobId);
        state.setProjectId(projectId);
        state.setStatus("QUEUED");
        state.setModel(currentModel());
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
        List<DeepSplitChunk> pendingChunks = new ArrayList<DeepSplitChunk>();
        for (DeepSplitChunk chunk : state.getChunks()) {
            if ("PENDING".equals(chunk.getStatus())) {
                pendingChunks.add(chunk);
            }
        }
        int parallelism = Math.min(currentChunkParallelism(), pendingChunks.size());
        log.info("Deep split job chunks prepared. projectId={}, jobId={}, pendingChunks={}, chunkParallelism={}",
                projectId, jobId, pendingChunks.size(), parallelism);
        if (parallelism <= 1) {
            for (DeepSplitChunk chunk : pendingChunks) {
                processChunk(state, evidenceBlocks, chunk);
            }
        } else {
            runChunksInParallel(state, evidenceBlocks, pendingChunks, parallelism);
        }
        synchronized (state) {
            finishState(state);
            saveState(state);
        }
    }

    private void runChunksInParallel(final DeepSplitJobState state,
                                     final List<EvidenceBlock> evidenceBlocks,
                                     List<DeepSplitChunk> pendingChunks,
                                     int parallelism) {
        ExecutorService chunkExecutor = Executors.newFixedThreadPool(parallelism, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable);
                thread.setName("deep-split-chunk-" + state.getJobId() + "-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        for (final DeepSplitChunk chunk : pendingChunks) {
            futures.add(chunkExecutor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    processChunk(state, evidenceBlocks, chunk);
                    return null;
                }
            }));
        }
        chunkExecutor.shutdown();
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                markPendingChunksFailed(state, pendingChunks, "AI 深度审校任务被中断，请重试失败批次。");
                break;
            } catch (ExecutionException ex) {
                log.warn("Deep split chunk worker crashed, projectId={}, jobId={}",
                        state.getProjectId(), state.getJobId(), ex);
            }
        }
        try {
            if (!chunkExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                chunkExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            chunkExecutor.shutdownNow();
            markPendingChunksFailed(state, pendingChunks, "AI 深度审校任务被中断，请重试失败批次。");
        }
    }

    private void processChunk(DeepSplitJobState state,
                              List<EvidenceBlock> evidenceBlocks,
                              DeepSplitChunk chunk) {
        synchronized (state) {
            if (!"PENDING".equals(chunk.getStatus())) {
                return;
            }
            chunk.setStatus("RUNNING");
            chunk.setAttempts(chunk.getAttempts() + 1);
            state.setUpdatedAt(now());
            saveState(state);
        }
        try {
            List<EvidenceBlock> selectedEvidence = selectEvidenceBlocks(
                    evidenceBlocks, state.getOriginalRows(), chunk.getRowNumbers());
            String prompt = buildPrompt(state, chunk, selectedEvidence);
            String aiText = callAiWithRetries(state.getProjectId(), state.getJobId(),
                    chunk, prompt, selectedEvidence.size());
            List<DeepSplitSuggestion> suggestions = parseSuggestions(aiText);
            List<DeepSplitSuggestion> accepted = normalizeSuggestions(state, chunk, selectedEvidence, suggestions);
            synchronized (state) {
                mergeSuggestions(state.getSuggestions(), accepted);
                chunk.setSuggestionCount(accepted.size());
                chunk.setStatus("COMPLETED");
                chunk.setError("");
                state.setUpdatedAt(now());
                saveState(state);
            }
        } catch (RuntimeException ex) {
            synchronized (state) {
                chunk.setStatus("FAILED");
                chunk.setError(userFacingChunkError(ex));
                state.setUpdatedAt(now());
                saveState(state);
            }
            log.warn("Deep split chunk failed, projectId={}, jobId={}, chunkId={}",
                    state.getProjectId(), state.getJobId(), chunk.getId(), ex);
        }
    }

    private void markPendingChunksFailed(DeepSplitJobState state, List<DeepSplitChunk> chunks, String message) {
        synchronized (state) {
            for (DeepSplitChunk chunk : chunks) {
                if ("PENDING".equals(chunk.getStatus()) || "RUNNING".equals(chunk.getStatus())) {
                    chunk.setStatus("FAILED");
                    chunk.setError(message);
                }
            }
            state.setUpdatedAt(now());
            saveState(state);
        }
    }

    List<DeepSplitChunk> buildChunks(List<FunctionSplitRow> rows) {
        Map<String, List<Integer>> groups = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < rows.size(); i++) {
            FunctionSplitRow row = rows.get(i);
            String key = FunctionProcessKey.of(row);
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
                    && (estimatedChars + groupChars > currentChunkPromptChars() || groupCount >= MAX_GROUPS_PER_CHUNK)) {
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
            String key = FunctionProcessKey.of(row);
            List<Integer> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<Integer>();
                groups.put(key, group);
            }
            group.add(number);
        }

        List<DeepSplitChunk> result = new ArrayList<DeepSplitChunk>();
        DeepSplitChunk current = new DeepSplitChunk();
        int retryPromptBudget = Math.max(4000, currentChunkPromptChars() / 2);
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
        return FunctionProcessKey.process(key);
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

    List<Integer> selectGlobalDuplicateCandidateRows(List<FunctionSplitRow> rows,
                                                     List<Integer> currentRowNumbers) {
        if (rows == null || rows.isEmpty() || currentRowNumbers == null || currentRowNumbers.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Integer> current = new HashSet<Integer>(currentRowNumbers);
        Map<String, List<Integer>> outsideGroups = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < rows.size(); i++) {
            int number = i + 1;
            if (current.contains(number)) {
                continue;
            }
            String key = FunctionProcessKey.of(rows.get(i));
            List<Integer> numbers = outsideGroups.get(key);
            if (numbers == null) {
                numbers = new ArrayList<Integer>();
                outsideGroups.put(key, numbers);
            }
            numbers.add(number);
        }
        List<DuplicateCandidateGroup> ranked = new ArrayList<DuplicateCandidateGroup>();
        for (Map.Entry<String, List<Integer>> entry : outsideGroups.entrySet()) {
            int best = 0;
            for (Integer candidateNumber : entry.getValue()) {
                FunctionSplitRow candidate = rows.get(candidateNumber - 1);
                for (Integer currentNumber : currentRowNumbers) {
                    if (currentNumber == null || currentNumber < 1 || currentNumber > rows.size()) {
                        continue;
                    }
                    best = Math.max(best, duplicateCandidateScore(rows.get(currentNumber - 1), candidate));
                }
            }
            if (best >= 12) {
                ranked.add(new DuplicateCandidateGroup(entry.getValue(), best));
            }
        }
        Collections.sort(ranked, new Comparator<DuplicateCandidateGroup>() {
            @Override
            public int compare(DuplicateCandidateGroup left, DuplicateCandidateGroup right) {
                int score = Integer.compare(right.score, left.score);
                return score != 0 ? score : Integer.compare(left.rowNumbers.get(0), right.rowNumbers.get(0));
            }
        });
        List<Integer> result = new ArrayList<Integer>();
        for (DuplicateCandidateGroup candidate : ranked) {
            for (Integer number : candidate.rowNumbers) {
                if (result.size() >= MAX_GLOBAL_DUPLICATE_CANDIDATE_ROWS) {
                    return result;
                }
                result.add(number);
            }
        }
        return result;
    }

    private int duplicateCandidateScore(FunctionSplitRow left, FunctionSplitRow right) {
        int score = 0;
        if (sameNonBlank(left.getMovementType(), right.getMovementType())) {
            score += 5;
        }
        if (sameNonBlank(left.getSubProcessDescription(), right.getSubProcessDescription())) {
            score += 8;
        }
        if (sameNonBlank(left.getDataGroup(), right.getDataGroup())) {
            score += 5;
        }
        if (sameNonBlank(normalizeAttributes(left.getDataAttributes()), normalizeAttributes(right.getDataAttributes()))) {
            score += 8;
        }
        int processOverlap = overlap(grams(text(left.getFunctionProcess())), grams(text(right.getFunctionProcess())));
        score += Math.min(6, processOverlap);
        if (sameNonBlank(left.getLevel1Module(), right.getLevel1Module())
                && sameNonBlank(left.getFunctionalUser(), right.getFunctionalUser())) {
            score += 2;
        }
        return score;
    }

    private boolean sameNonBlank(String left, String right) {
        String leftValue = comparableForDuplicate(left);
        String rightValue = comparableForDuplicate(right);
        return StringUtils.hasText(leftValue) && leftValue.equals(rightValue);
    }

    private String comparableForDuplicate(String value) {
        return text(value).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。、；：“”‘’]+", "");
    }

    private String normalizeAttributes(String value) {
        List<String> attributes = new ArrayList<String>();
        for (String part : text(value).split("[、,，;；|/\\s]+")) {
            String attribute = comparableForDuplicate(part);
            if (StringUtils.hasText(attribute) && !attributes.contains(attribute)) {
                attributes.add(attribute);
            }
        }
        Collections.sort(attributes);
        return String.join("|", attributes);
    }

    String buildPrompt(DeepSplitJobState state, DeepSplitChunk chunk, List<EvidenceBlock> evidence) {
        boolean deepSeekPrompt = usesDeepSeekPrompt();
        StringBuilder builder = new StringBuilder();
        builder.append("你是资深 COSMIC 功能点分析专家。请对当前批次的完整功能过程进行结构化二次审校。\n");
        builder.append("只输出确有必要且可以直接采纳的变更建议；正确内容不要输出建议。\n");
        builder.append("这是送审前终审，不是文字润色。已经满足 COSMIC 基本规则的功能过程视为收敛，不要为了“更细一点/更完整一点/表达更顺一点”反复提出建议。\n");
        builder.append("允许的 operation 只有 SPLIT_FUNCTION、ADD_MISSING_MOVEMENT、REMOVE_UNSUPPORTED_MOVEMENT、ADJUST_MOVEMENT_STRUCTURE、ADJUST_REUSE_CLASSIFICATION。\n");
        builder.append("- SPLIT_FUNCTION：一个功能过程包含多个独立触发事件或业务结果，需要拆为多个功能过程。\n");
        builder.append("- ADD_MISSING_MOVEMENT：当前功能过程遗漏了有明确依据的 E/R/W/X 数据移动。\n");
        builder.append("- REMOVE_UNSUPPORTED_MOVEMENT：当前功能过程包含与业务动作不符且没有原文依据的多余数据移动；targetRowNumbers 必须包含该功能过程需要整体替换的全部原行，replacementRows 给出删除多余行后的完整剩余行。\n");
        builder.append("- ADJUST_MOVEMENT_STRUCTURE：当前功能过程需要在不增加行数的情况下替换错误的数据移动类型或数据组；targetRowNumbers 必须包含整体替换的全部原行，replacementRows 给出调整后的完整行。\n");
        builder.append("- ADJUST_REUSE_CLASSIFICATION：根据历史判例或全表语义查重，调整功能级重复/子过程级复用的 reuseDegree 与 CFP；功能级重复或包含为利旧/0，用户目标不同但数据移动复用为复用/精确 1/3。\n");
        builder.append("重要约束：\n");
        builder.append("1. 不得为了增加 CFP 凭空创造功能、数据组或属性。\n");
        builder.append("2. replacementRows 必须给出采纳建议后的完整替换行，而不是只给变化字段。\n");
        builder.append("3. targetRowNumbers 必须使用输入中的 rowNumber，采用 1 开始的原表行号。\n");
        builder.append("4. 每个 replacementRows 元素必须包含 projectName,level1Module,level2Module,level3Module,functionalUser,triggerEvent,functionProcess,functionDescription,subProcessDescription,movementType,dataGroup,dataAttributes,sequenceDiagram,reuseDegree,cfp,evidence,sourceReferences。\n");
        builder.append("5. 每个功能过程必须由初始触发 E 开始，并以 W 或 X 形成业务结果；R/W 只用于持久化数据组，X 必须跨软件边界输出。后续 E 只允许表示本系统发出外部请求 X 后、同一业务目标内返回的同步响应。\n");
        builder.append("6. sourceReferences 只能填写下方实际提供的 REQ-Bxxxx 或 EXCEL-ROW-x 编号；evidence 简要写明依据。\n");
        builder.append("7. 如果证据不足，宁可不输出建议，不要猜测。\n");
        builder.append("8. 禁止输出只改变名称、描述、措辞、模块归类或顺序的建议；必须改变功能边界、数据移动结构、重复计量或 CFP 才能输出。\n");
        builder.append("9. 对同一功能过程，不要把已经可计量的 E/R/W/X 继续拆成更碎颗粒；只有存在独立触发事件、独立业务目的或独立业务结果时才允许拆分。\n");
        builder.append("9.1 每个功能过程至少包含 2 行，首行必须是初始触发 E，末行必须是 W 或 X；允许有依据的 X-E 外部同步往返，禁止无依据的连续 E。按 v2.8 三行骨架审校：首 E + 主体（W 或 R）+ 末 X，多出的中间 R/W（读取参照/配置/上期数据/维表、写中间表或第 2 个目标表）用 REMOVE_UNSUPPORTED_MOVEMENT 删除；超过 4 行只允许用户触发的外部调用链 E-X-E-W-X 和需求明示的第二接收方 X。\n");
        builder.append("9.2 校验、计算、解析、匹配、过滤、排序和格式转换是数据操作，不单独计数据移动；只有真实跨边界输入输出或持久化 R/W 才计量。\n");
        builder.append("9.3 功能过程名称必须是“动作+业务对象”。翻页、勾选/取消勾选、图层切换、自动填充、高亮渲染等纯前端动作不独立计量；如有后台交互，应审查真实数据组后合并或改写。\n");
        builder.append("9.4 保存/记录/删除技术日志通常不独立计量；日志查询或日志管理只有在原文明示独立功能用户、触发和数据移动时才保留。“暂存”只有进入持久性存储才计 W。\n");
        builder.append("9.5 数据属性应先检索表格字段、接口/报文参数、正文明确业务数据项和同一功能过程上下文；正文中的“六级地址、故障原因、ret、P01、U13”等可直接作为属性。同一数据组传递同一批数据时完整复用已知属性；无载荷定时/时钟触发 E 属性填“调度时间、调度任务名称”。数据属性至少 2 个，数据组用中文业务名。新疆项目暂不因“字段名（待确认）”混合表达提出建议；仍不得虚构字段或使用“同上/见上”。\n");
        builder.append("9.6 必须做全表语义查重。复用判定三要素为子过程描述、数据移动类型、数据属性：同一或不同功能过程中，三项完全相同且无跨层/跨边界依据按利旧/0 或合并，两项相同且无跨层/跨边界依据按复用/精确 1/3 核实。先判断功能级重复/包含，再判断子过程级复用；不能因功能名不同全部判新增，也不能因一行复用把整个新用户目标判 0。统一映射为新增=1、复用=精确 1/3、利旧=0；同一功能过程统一一个复用度，定时首 E 与日志 W 可单独为 1/3。\n");
        builder.append("9.7 同一数据表、接口或业务对象不得按单字段、单统计维度、正反前端操作或同一文件的不同下载环节过度拆分。查询与展示同一对象通常合并为一个 E-R-X，独立触发/数据组/输出结果除外。\n");
        builder.append("9.8 历史评审批注优先于通用模板：N/NM/UIUE/C/NR/算法本体/需求文档不涉及通常为 0；OLD、功能重复、包含在、合并为通常是功能级 0；数字行号、明确复用及通用能力通常进入 1/3 候选。上传、下载、导入导出、短信、日志、常见查询、配置权限必须先查历史。\n");
        builder.append("9.9 “读取XX相关数据”R、“保存XX处理结果”W、“返回XX处理结果”X、“输出分页和排序信息”X 是模板化补齐行，评审判 N，应用 REMOVE_UNSUPPORTED_MOVEMENT 删除；用户导出中“生成导出文件”W、导入中“读取/解析导入文件”R 同样删除。不得使用请求编号/操作人/请求时间、业务编号/状态/内容、记录编号/处理状态/时间、结果编号/状态/提示信息等无原文依据的泛化属性制造差异。\n");
        builder.append("9.10 一个 R/W 只能承载一个逻辑持久化数据组。同一触发和用户目标下，若原文明示读取或更新多个不同业务对象、业务表、台账或记录，不拆成功能过程，但必须用 ADD_MISSING_MOVEMENT 将合并行整体替换为按对象分别列出的多个 R/W；同一对象仅更新多个字段仍只保留一个 W。"
                + "例如“更新调拨分派单状态为已出库，并更新库存及全生命周期流转记录”应保留在同一功能过程中，拆为调拨分派单、库存、全生命周期流转记录 3 个 W。\n");
        builder.append("9.12 v2.8 拆分约束：内部事件（上一环节完成后、文件生成事件、轮询、状态回传、系统自动触发）触发的二段过程应并入上游；稽核/核查/分类/清除/归档/状况监测/异常告警/异常处置/缺失变更/重报/历史查询/失败原因查看等模板链步骤、同一指标的粒度/制式/Topic/日月表变体、页面交互与表单步骤过程，按功能级重复或不成立用 ADJUST_REUSE_CLASSIFICATION 判利旧/0。\n");
        builder.append("9.11 新疆项目中，查询、导出、发送等以 R/X 为主的功能或业务对象名称同时包含“派发/派单”等词时，不得机械合并动作模板并提出补 W 建议；只有独立派发功能，或原文明示更新派发状态、保存派发结果或写入持久化数据组时才保留/补充 W。\n");
        builder.append("10. 按统一动作规则检查组合，但必须同时看功能用户、触发事件、系统边界、数据组和结果是否留存，不能只看功能过程名称：\n");
        builder.append("- 本系统向外部平台发送请求 X 后，对方在同一调用链、同一用户目标内同步返回业务数据时，响应进入本系统可再次记 E，可形成 E-X-E-W-X 或多组 X-E。"
                + "若外部平台稍后再调用本系统回调/上报接口，则属于新的独立触发，必须用 SPLIT_FUNCTION 拆分；被调用接口的 ReturnCode/ReturnMessage 是第二个功能过程的 X。\n");
        builder.append("- E 必须描述真实进入本系统的数据或事件，并按真实发起方使用自然业务语言：用户/UI 主动触发写“发起XXX查询，提交具体查询条件”或“提交XXX业务信息”；"
                + "外部平台上报/回调才写“接收XXX上报数据”，X 后同步响应写“获取XXX同步响应数据”。出现“请求请求”“接收+功能过程名+请求”或“接收向XX平台发送……”时，应结合原文修正数据方向和子过程名称。\n");
        builder.append("- 数据组和属性必须逐项来自原文表格、接口定义、正文明确业务项或同一功能过程已知属性；“请求编号、操作人、请求时间、业务状态、处理时间”等通用字段没有依据时必须删除。新疆项目不因“字段名（待确认）”混合表达提出建议，也不因功能名称含多个动作词或出现“配置”提出建议；只有真实 E/R/W/X、数据组或业务边界存在问题时才建议调整。\n");
        if (splitRuleConfigService != null) {
            builder.append(splitRuleConfigService.getEffectiveRulesPrompt()).append("【当前生效规则结束】\n");
        } else {
            builder.append(ActionMovementRule.canonicalPromptRules());
        }
        builder.append("原文和数据组支持的组合应保留；缺少数据移动用 ADD_MISSING_MOVEMENT，多余且无依据的数据移动用 REMOVE_UNSUPPORTED_MOVEMENT，"
                + "需要等行数替换错误类型或数据组时用 ADJUST_MOVEMENT_STRUCTURE，历史/全表查重调整 CFP 时用 ADJUST_REUSE_CLASSIFICATION。\n");
        if (!state.isSourceAvailable()) {
            builder.append("11. 当前没有原始需求文档。不得新增表中没有体现的业务功能；只能依据 EXCEL-ROW 现有字段修正功能边界、重复项或明显错误的数据移动。\n");
        } else {
            builder.append("11. 当前存在原始需求证据。涉及新增、拆分、补充或删除数据移动的建议，sourceReferences 必须至少包含一个 REQ-Bxxxx 证据编号，不能只依据 EXCEL-ROW。\n");
        }
        builder.append(deepSeekPrompt
                ? "输出顶层为 suggestions 数组的严格 JSON 对象，不要 Markdown；没有建议时输出 "
                        + DeepSeekPromptProfile.emptyJson(AiPromptTask.DEEP_REVIEW) + "。元素格式：\n"
                : "输出严格 JSON 数组，不要 Markdown。元素格式：\n");
        builder.append("{\"operation\":\"SPLIT_FUNCTION\",\"title\":\"\",\"reason\":\"\",\"evidence\":\"\",\"sourceReferences\":[\"REQ-B0001\"],\"confidence\":0.0,\"targetRowNumbers\":[1],\"replacementRows\":[{...}]}\n\n");
        builder.append("【当前拆分行】\n");
        for (Integer number : chunk.getRowNumbers()) {
            if (number == null || number < 1 || number > state.getOriginalRows().size()) {
                continue;
            }
            builder.append("EXCEL-ROW-").append(number).append(" ");
            builder.append(toJson(new IndexedRow(number, state.getOriginalRows().get(number - 1)))).append("\n");
        }
        List<Integer> duplicateCandidateRows = selectGlobalDuplicateCandidateRows(
                state.getOriginalRows(), chunk.getRowNumbers());
        builder.append("\n【全表语义查重候选】\n");
        builder.append("以下行来自当前批次之外，只用于比较历史/全表重复与复用；不得作为 targetRowNumbers。\n");
        if (duplicateCandidateRows.isEmpty()) {
            builder.append("无高相似候选。\n");
        } else {
            for (Integer number : duplicateCandidateRows) {
                builder.append("EXCEL-ROW-").append(number).append(" ");
                builder.append(toJson(new IndexedRow(number, state.getOriginalRows().get(number - 1)))).append("\n");
            }
        }
        builder.append("\n【检索到的原始需求证据】\n");
        if (evidence.isEmpty()) {
            builder.append("无原始需求文本。\n");
        } else {
            for (EvidenceBlock block : evidence) {
                builder.append("[").append(block.getId()).append("] ").append(block.getText()).append("\n");
            }
        }
        String prompt = builder.toString();
        return deepSeekPrompt
                ? DeepSeekPromptProfile.specialize(
                        AiPromptTask.DEEP_REVIEW, prompt, splitRuleConfigService.getRuleVersion())
                : prompt;
    }

    private String callAiWithRetries(String projectId, String jobId, DeepSplitChunk chunk,
                                     String prompt, int evidenceBlockCount) {
        RuntimeException last = null;
        int attempts = currentChunkMaxRetries() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long startedAt = System.nanoTime();
            String context = "projectId=" + projectId + ", jobId=" + jobId + ", chunkId=" + chunk.getId()
                    + ", attempt=" + attempt + "/" + attempts + ", rows=" + chunk.getRowNumbers().size()
                    + ", evidenceBlocks=" + evidenceBlockCount + ", promptChars=" + prompt.length()
                    + ", model=" + currentModel() + ", timeoutSeconds=" + currentChunkTimeoutSeconds();
            log.info("Deep split AI call started: {}", context);
            appendCallLog(projectId, jobId, "START " + context);
            try {
                String response;
                if (aiModelConfigService != null) {
                    response = aiModelConfigService.generateForTask(
                            prompt, AiPromptTask.DEEP_REVIEW, true, 0);
                } else if (usesDeepSeekPrompt()) {
                    AiModelProfile profile = new AiModelProfile();
                    profile.setProvider("deepseek");
                    profile.setBaseUrl(baseUrl);
                    profile.setModel(model);
                    response = new DeepSeekChatClient(objectMapper).generate(
                            profile, resolveApiKey(), AiPromptTask.DEEP_REVIEW,
                            prompt, chunkTimeoutSeconds, 0);
                } else {
                    ChatLanguageModel chatModel = OpenAiChatModel.builder()
                            .baseUrl(baseUrl)
                            .apiKey(resolveApiKey())
                            .modelName(model)
                            .temperature(0.0)
                            .timeout(Duration.ofSeconds(chunkTimeoutSeconds))
                            .maxRetries(0)
                            .build();
                    response = chatModel.generate(prompt);
                }
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
            if (json.startsWith("{")) {
                JsonSuggestionWrapper wrapper = objectMapper.readValue(json, JsonSuggestionWrapper.class);
                return wrapper.getSuggestions() == null
                        ? Collections.<DeepSplitSuggestion>emptyList() : wrapper.getSuggestions();
            }
            return objectMapper.readValue(json, new TypeReference<List<DeepSplitSuggestion>>() {});
        } catch (IOException ex) {
            throw new BusinessException("AI 深度审校结果不是有效 JSON，请重试当前批次。", ex);
        }
    }

    List<DeepSplitSuggestion> normalizeSuggestions(DeepSplitJobState state,
                                                   DeepSplitChunk chunk,
                                                   List<EvidenceBlock> evidence,
                                                   List<DeepSplitSuggestion> rawSuggestions) {
        List<DeepSplitSuggestion> result = new ArrayList<DeepSplitSuggestion>();
        Set<Integer> chunkRows = new HashSet<Integer>(chunk.getRowNumbers());
        Set<String> validReferences = new HashSet<String>();
        for (Integer number : chunk.getRowNumbers()) {
            validReferences.add("EXCEL-ROW-" + number);
        }
        for (Integer number : selectGlobalDuplicateCandidateRows(
                state.getOriginalRows(), chunk.getRowNumbers())) {
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
            if (state.isSourceAvailable() && !hasRequirementReference(references)) {
                continue;
            }
            FunctionSplitRow base = state.getOriginalRows().get(targets.get(0) - 1);
            List<FunctionSplitRow> replacements = normalizeReplacementRows(suggestion.getReplacementRows(), base);
            if (replacements.isEmpty()) {
                continue;
            }
            attachSuggestionEvidence(replacements, suggestion.getEvidence(), references);
            if ("SPLIT_FUNCTION".equals(suggestion.getOperation()) && distinctProcesses(replacements) < 2) {
                continue;
            }
            if ("ADD_MISSING_MOVEMENT".equals(suggestion.getOperation()) && replacements.size() <= targets.size()) {
                continue;
            }
            if ("REMOVE_UNSUPPORTED_MOVEMENT".equals(suggestion.getOperation()) && replacements.size() >= targets.size()) {
                continue;
            }
            if ("ADJUST_REUSE_CLASSIFICATION".equals(suggestion.getOperation())
                    && !isReuseClassificationOnlyChange(state.getOriginalRows(), targets, replacements)) {
                continue;
            }
            double confidence = Math.max(0, Math.min(1, suggestion.getConfidence()));
            if (confidence < MIN_ACCEPTED_CONFIDENCE) {
                continue;
            }
            if (!hasMaterialChange(state.getOriginalRows(), targets, replacements)) {
                continue;
            }
            if (ruleValidationService != null
                    && !"ADJUST_REUSE_CLASSIFICATION".equals(suggestion.getOperation())
                    && !ruleValidationService.generatedBlockingIssues(replacements).isEmpty()) {
                continue;
            }
            suggestion.setTargetRowNumbers(targets);
            suggestion.setSourceReferences(references);
            suggestion.setReplacementRows(replacements);
            suggestion.setConfidence(confidence);
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

    private boolean hasRequirementReference(List<String> references) {
        if (references == null) {
            return false;
        }
        for (String reference : references) {
            if (text(reference).toUpperCase(Locale.ROOT).startsWith("REQ-B")) {
                return true;
            }
        }
        return false;
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
            row.setFunctionProcessId(base.getFunctionProcessId());
            row.setProjectName(firstText(source.getProjectName(), base.getProjectName()));
            // Requirement hierarchy comes from Word evidence and is immutable.
            row.setLevel1Module(text(base.getLevel1Module()));
            row.setLevel2Module(text(base.getLevel2Module()));
            row.setLevel3Module(text(base.getLevel3Module()));
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
            row.setEvidence(firstText(source.getEvidence(), base.getEvidence()));
            row.setSourceReferences(source.getSourceReferences() == null
                    ? new ArrayList<String>() : new ArrayList<String>(source.getSourceReferences()));
            row.setSourceHeadingPaths(base.getSourceHeadingPaths() == null
                    ? new ArrayList<String>() : new ArrayList<String>(base.getSourceHeadingPaths()));
            result.add(row);
        }
        return result;
    }

    private void attachSuggestionEvidence(List<FunctionSplitRow> rows,
                                          String evidence,
                                          List<String> references) {
        for (FunctionSplitRow row : rows) {
            if (!StringUtils.hasText(row.getEvidence())) {
                row.setEvidence(text(evidence));
            }
            if (row.getSourceReferences() == null || row.getSourceReferences().isEmpty()) {
                row.setSourceReferences(new ArrayList<String>(references));
            }
        }
    }

    private int distinctProcesses(List<FunctionSplitRow> rows) {
        Set<String> values = new HashSet<String>();
        for (FunctionSplitRow row : rows) {
            values.add(text(row.getFunctionProcess()));
        }
        return values.size();
    }

    private boolean hasMaterialChange(List<FunctionSplitRow> originalRows,
                                      List<Integer> targetNumbers,
                                      List<FunctionSplitRow> replacementRows) {
        List<String> before = new ArrayList<String>();
        for (Integer number : targetNumbers) {
            if (number != null && number >= 1 && number <= originalRows.size()) {
                before.add(materialSignature(originalRows.get(number - 1)));
            }
        }
        List<String> after = new ArrayList<String>();
        for (FunctionSplitRow row : replacementRows) {
            after.add(materialSignature(row));
        }
        Collections.sort(before);
        Collections.sort(after);
        return !before.equals(after);
    }

    private boolean isReuseClassificationOnlyChange(List<FunctionSplitRow> originalRows,
                                                    List<Integer> targetNumbers,
                                                    List<FunctionSplitRow> replacementRows) {
        if (targetNumbers.size() != replacementRows.size()) {
            return false;
        }
        List<String> before = new ArrayList<String>();
        for (Integer number : targetNumbers) {
            if (number == null || number < 1 || number > originalRows.size()) {
                return false;
            }
            before.add(structureSignature(originalRows.get(number - 1)));
        }
        List<String> after = new ArrayList<String>();
        for (FunctionSplitRow row : replacementRows) {
            after.add(structureSignature(row));
        }
        Collections.sort(before);
        Collections.sort(after);
        return before.equals(after);
    }

    private String structureSignature(FunctionSplitRow row) {
        return text(row.getFunctionProcess()) + "|"
                + text(row.getSubProcessDescription()) + "|"
                + text(row.getMovementType()).toUpperCase(Locale.ROOT) + "|"
                + text(row.getDataGroup()) + "|" + text(row.getDataAttributes());
    }

    private String materialSignature(FunctionSplitRow row) {
        return text(row.getMovementType()).toUpperCase(Locale.ROOT) + "|"
                + text(row.getDataGroup()) + "|"
                + text(row.getDataAttributes()) + "|"
                + normalizeReuse(row.getReuseDegree()) + "|"
                + cfpByReuse(normalizeReuse(row.getReuseDegree()));
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
            if (selected.size() >= MAX_EVIDENCE_BLOCKS || chars + block.getText().length() > currentEvidenceChars()) {
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
        response.setChunkParallelism(currentChunkParallelism());
        int completed = 0;
        int running = 0;
        int failed = 0;
        for (DeepSplitChunk chunk : state.getChunks()) {
            if ("COMPLETED".equals(chunk.getStatus())) {
                completed++;
            } else if ("RUNNING".equals(chunk.getStatus())) {
                running++;
            } else if ("FAILED".equals(chunk.getStatus())) {
                failed++;
            }
        }
        response.setCompletedChunks(completed);
        response.setRunningChunks(running);
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
        if (text.startsWith("{")) {
            return text;
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
        if ("REMOVE_UNSUPPORTED_MOVEMENT".equals(operation)) {
            return "删除无依据数据移动：" + process;
        }
        if ("ADJUST_MOVEMENT_STRUCTURE".equals(operation)) {
            return "调整数据移动结构：" + process;
        }
        if ("ADJUST_REUSE_CLASSIFICATION".equals(operation)) {
            return "调整复用与 CFP：" + process;
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

    private AiModelProfile activeProfile() {
        return aiModelConfigService == null ? null : aiModelConfigService.getActiveProfile();
    }

    private boolean hasConfiguredApiKey() {
        AiModelProfile profile = activeProfile();
        return profile == null ? StringUtils.hasText(resolveApiKey())
                : StringUtils.hasText(profile.getApiKey()) || StringUtils.hasText(resolveApiKey());
    }

    private String currentModel() {
        AiModelProfile profile = activeProfile();
        return profile == null ? model : profile.getModel();
    }

    private boolean usesDeepSeekPrompt() {
        AiModelProfile profile = activeProfile();
        return profile == null
                ? DeepSeekPromptProfile.matches("", baseUrl, model)
                : DeepSeekPromptProfile.matches(profile);
    }

    private long currentChunkTimeoutSeconds() {
        AiModelProfile profile = activeProfile();
        return profile == null ? chunkTimeoutSeconds : Math.max(20, profile.getChunkTimeoutSeconds());
    }

    private int currentChunkMaxRetries() {
        AiModelProfile profile = activeProfile();
        return profile == null ? maxRetries : Math.max(0, profile.getChunkMaxRetries());
    }

    private int currentChunkPromptChars() {
        AiModelProfile profile = activeProfile();
        return profile == null ? chunkPromptChars : Math.max(1000, profile.getChunkPromptChars());
    }

    private int currentEvidenceChars() {
        AiModelProfile profile = activeProfile();
        return profile == null ? evidenceChars : Math.max(500, profile.getEvidenceChars());
    }

    private int currentMaxRequestRows() {
        AiModelProfile profile = activeProfile();
        return profile == null ? maxRequestRows : Math.max(MIN_REQUEST_ROWS_LIMIT, profile.getMaxRequestRows());
    }

    private int currentChunkParallelism() {
        AiModelProfile profile = activeProfile();
        return profile == null ? chunkParallelism : Math.max(1, profile.getChunkParallelism());
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

    private static class DuplicateCandidateGroup {
        private final List<Integer> rowNumbers;
        private final int score;

        private DuplicateCandidateGroup(List<Integer> rowNumbers, int score) {
            this.rowNumbers = new ArrayList<Integer>(rowNumbers);
            this.score = score;
        }
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

    private static class JsonSuggestionWrapper {
        private List<DeepSplitSuggestion> suggestions;

        public List<DeepSplitSuggestion> getSuggestions() {
            return suggestions;
        }

        public void setSuggestions(List<DeepSplitSuggestion> suggestions) {
            this.suggestions = suggestions;
        }
    }
}
