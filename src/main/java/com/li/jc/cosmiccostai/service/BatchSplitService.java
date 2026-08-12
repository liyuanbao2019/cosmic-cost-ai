package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.BatchSplitConfirmRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitCreateRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitItemResponse;
import com.li.jc.cosmiccostai.dto.BatchSplitJobResponse;
import com.li.jc.cosmiccostai.dto.DocumentChapterItem;
import com.li.jc.cosmiccostai.dto.DocumentChapterSelectionResponse;
import com.li.jc.cosmiccostai.dto.ParseResponse;
import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.dto.ProjectMaterialFileResponse;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Coordinates the two-phase multi-document split workflow. Preparation only parses
 * documents. AI work is not scheduled until every item has an immutable, confirmed
 * input snapshot.
 */
@Service
public class BatchSplitService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ID_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final FileStorageService fileStorageService;
    private final DocumentParseService documentParseService;
    private final DocumentChapterSelectionService documentChapterSelectionService;
    private final RequirementInputService requirementInputService;
    private final SmartSplitPipelineService smartSplitPipelineService;
    private final ProjectMaterialExportService projectMaterialExportService;
    private final TaskExecutor batchPreparationExecutor;
    private final TaskExecutor batchSplitExecutor;
    private final int maxConcurrentExecutions;
    private final int maxConcurrentExecutionsPerBatch;
    private final Path batchBaseDir;
    private final int maxItems;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Object> batchLocks = new ConcurrentHashMap<String, Object>();
    private final ConcurrentMap<String, String> assignedProjects = new ConcurrentHashMap<String, String>();
    private final Object executionScheduleLock = new Object();
    private final Map<String, Deque<String>> pendingExecutions = new LinkedHashMap<String, Deque<String>>();
    private final Deque<String> pendingBatchOrder = new ArrayDeque<String>();
    private final Set<String> scheduledExecutions = new HashSet<String>();
    private final Map<String, Integer> activeExecutionsByBatch = new HashMap<String, Integer>();
    private int activeExecutionCount;

    public BatchSplitService(FileStorageService fileStorageService,
                             DocumentParseService documentParseService,
                             DocumentChapterSelectionService documentChapterSelectionService,
                             RequirementInputService requirementInputService,
                             SmartSplitPipelineService smartSplitPipelineService,
                             ProjectMaterialExportService projectMaterialExportService,
                             @Qualifier("batchPreparationExecutor") TaskExecutor batchPreparationExecutor,
                             @Qualifier("batchSplitExecutor") TaskExecutor batchSplitExecutor,
                             @Value("${cosmic.ai.batch-split.worker-count:24}") int maxConcurrentExecutions,
                             @Value("${cosmic.ai.batch-split.max-concurrency-per-batch:23}") int maxConcurrentExecutionsPerBatch,
                             @Value("${cosmic.storage.base-dir:storage}") String storageBaseDir,
                             @Value("${cosmic.ai.batch-split.max-items:50}") int maxItems) {
        this.fileStorageService = fileStorageService;
        this.documentParseService = documentParseService;
        this.documentChapterSelectionService = documentChapterSelectionService;
        this.requirementInputService = requirementInputService;
        this.smartSplitPipelineService = smartSplitPipelineService;
        this.projectMaterialExportService = projectMaterialExportService;
        this.batchPreparationExecutor = batchPreparationExecutor;
        this.batchSplitExecutor = batchSplitExecutor;
        this.maxConcurrentExecutions = Math.max(1, maxConcurrentExecutions);
        this.maxConcurrentExecutionsPerBatch = Math.max(1,
                Math.min(this.maxConcurrentExecutions, maxConcurrentExecutionsPerBatch));
        this.batchBaseDir = Paths.get(storageBaseDir).resolve("batch-split");
        this.maxItems = Math.max(1, maxItems);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void recoverPersistedBatches() {
        if (!Files.exists(batchBaseDir)) {
            return;
        }
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(batchBaseDir, "BS*")) {
            for (Path directory : directories) {
                if (!Files.isDirectory(directory) || !Files.exists(directory.resolve("state.json"))) {
                    continue;
                }
                String batchId = directory.getFileName().toString();
                synchronized (lockFor(batchId)) {
                    BatchSplitState state;
                    try {
                        state = loadStateUnlocked(batchId);
                    } catch (BusinessException ex) {
                        continue;
                    }
                    boolean stateChanged = false;
                    if ("GENERATING".equals(state.getProjectMaterialStatus())
                            || ("PENDING".equals(state.getProjectMaterialStatus())
                            && allItemsCompleted(state))) {
                        state.setProjectMaterialStatus("FAILED");
                        state.setProjectMaterialMessage("服务重启已停止项目级送审材料生成，未自动恢复；请手动点击重试。");
                        stateChanged = true;
                    }
                    for (BatchSplitItemState item : state.getItems()) {
                        if (!"COMPLETED".equals(item.getStatus())) {
                            assignedProjects.putIfAbsent(item.getProjectId(), batchId);
                        }
                        if ("PARSE_QUEUED".equals(item.getStatus())
                                || "PREPARING".equals(item.getStatus())
                                || "QUEUED".equals(item.getStatus())
                                || "RUNNING".equals(item.getStatus())) {
                            item.setStatus("FAILED");
                            item.setStage("RESTART_INTERRUPTED");
                            item.setStageMessage("服务重启后保持停止，等待手动恢复");
                            item.setErrorMessage("服务重启已停止该任务，未自动恢复；点击“恢复中断项”后才会重新排队。");
                            item.setFinishedAt(now());
                            stateChanged = true;
                        } else if ("FAILED".equals(item.getStatus())
                                && StringUtils.hasText(item.getErrorMessage())) {
                            String compactError = compactErrorMessage(item.getErrorMessage());
                            if (!compactError.equals(item.getErrorMessage())) {
                                item.setErrorMessage(compactError);
                                stateChanged = true;
                            }
                        }
                    }
                    if (stateChanged) {
                        recomputeState(state);
                        saveStateUnlocked(state);
                    }
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("恢复批量拆分任务失败。", ex);
        }
    }

    public BatchSplitJobResponse create(BatchSplitCreateRequest request) {
        List<String> projectIds = normalizeProjectIds(request == null ? null : request.getProjectIds());
        if (projectIds.isEmpty()) {
            throw new BusinessException("请至少选择一个需求文档。");
        }
        if (projectIds.size() > maxItems) {
            throw new BusinessException("单个批次最多允许 " + maxItems + " 个需求文档。");
        }

        String batchId = generateBatchId();
        BatchSplitState state = new BatchSplitState();
        state.setBatchId(batchId);
        state.setSplitLevel(normalizeSplitLevel(request == null ? null : request.getSplitLevel()));
        state.setProjectMaterialStatus("PROJECT".equals(state.getSplitLevel()) ? "PENDING" : "NOT_REQUIRED");
        state.setStatus("PREPARING");
        state.setCreatedAt(now());
        state.setUpdatedAt(state.getCreatedAt());

        List<String> reserved = new ArrayList<String>();
        try {
            for (String projectId : projectIds) {
                ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
                validateBatchProject(metadata);
                String existingBatch = assignedProjects.putIfAbsent(projectId, batchId);
                if (existingBatch != null && !batchId.equals(existingBatch)) {
                    throw new BusinessException("文档正在另一个批次中处理：" + metadata.getSourceFileName());
                }
                reserved.add(projectId);
                BatchSplitItemState item = new BatchSplitItemState();
                item.setProjectId(projectId);
                item.setProjectName(metadata.getName());
                item.setSourceFileName(metadata.getSourceFileName());
                item.setExistingRows(isExistingRowsProject(metadata));
                item.setStatus("PARSE_QUEUED");
                item.setStage("WAITING_PARSE");
                item.setStageMessage("等待进入文档解析队列");
                state.getItems().add(item);
            }
            associateProjects(state);
            saveState(state);
        } catch (RuntimeException ex) {
            for (String projectId : reserved) {
                assignedProjects.remove(projectId, batchId);
            }
            throw ex;
        }

        for (String projectId : projectIds) {
            submitPreparation(batchId, projectId);
        }
        return get(batchId);
    }

    private void associateProjects(BatchSplitState state) {
        for (BatchSplitItemState item : state.getItems()) {
            ProjectMetadata metadata = fileStorageService.loadMetadata(item.getProjectId());
            metadata.setSplitLevel(normalizeSplitLevel(state.getSplitLevel()));
            metadata.setBatchId(state.getBatchId());
            fileStorageService.saveMetadata(metadata);
        }
    }

    public BatchSplitJobResponse get(String batchId) {
        return toResponse(loadState(batchId));
    }

    public BatchSplitJobResponse confirm(String batchId,
                                         String projectId,
                                         BatchSplitConfirmRequest request) {
        BatchSplitState current = loadState(batchId);
        BatchSplitItemState currentItem = findItem(current, projectId);
        if (isExecutionStarted(current)) {
            throw new BusinessException("批量拆分已经开始，AI 输入已锁定，不能继续修改。");
        }
        if (!"AWAITING_CONFIRMATION".equals(currentItem.getStatus())
                && !"CONFIRMED".equals(currentItem.getStatus())) {
            throw new BusinessException("该文档尚未完成解析，暂不能确认 AI 输入。");
        }

        List<String> chapterIds = request == null || request.getChapterIds() == null
                ? Collections.<String>emptyList() : request.getChapterIds();
        if (!chapterIds.isEmpty()) {
            documentChapterSelectionService.apply(projectId, chapterIds);
        }
        String aiInput = request == null ? "" : trim(request.getAiInput());
        if (!StringUtils.hasText(aiInput)) {
            aiInput = readCurrentInput(projectId);
        }
        if (!StringUtils.hasText(aiInput)) {
            throw new BusinessException("AI 输入内容不能为空，请选择章节或补充需求内容。");
        }

        requirementInputService.saveManualInput(projectId, aiInput);
        writeInputSnapshot(batchId, projectId, aiInput);
        final String inputHash = sha256(aiInput);
        final int inputLength = aiInput.length();
        final List<String> selectedIds = new ArrayList<String>(chapterIds);
        updateItem(batchId, projectId, new ItemMutation() {
            @Override
            public void apply(BatchSplitItemState item) {
                item.setStatus("CONFIRMED");
                item.setStage("INPUT_CONFIRMED");
                item.setSelectedChapterIds(selectedIds);
                item.setConfirmedAt(now());
                item.setInputHash(inputHash);
                item.setInputLength(inputLength);
                item.setErrorMessage(null);
            }
        });
        return get(batchId);
    }

    public BatchSplitJobResponse start(String batchId) {
        List<String> projectIds = new ArrayList<String>();
        synchronized (lockFor(batchId)) {
            BatchSplitState state = loadStateUnlocked(batchId);
            if (isExecutionStarted(state)) {
                throw new BusinessException("该批次已经开始拆分，请勿重复提交。");
            }
            List<String> blockers = new ArrayList<String>();
            for (BatchSplitItemState item : state.getItems()) {
                if (!"CONFIRMED".equals(item.getStatus())) {
                    blockers.add(item.getSourceFileName() + "（" + statusText(item.getStatus()) + "）");
                    continue;
                }
                if (item.isExistingRows()) {
                    continue;
                }
                String currentInput = readCurrentInput(item.getProjectId());
                if (!StringUtils.hasText(item.getInputHash())
                        || !item.getInputHash().equals(sha256(currentInput))) {
                    item.setStatus("AWAITING_CONFIRMATION");
                    item.setStage("INPUT_CHANGED");
                    item.setConfirmedAt(null);
                    item.setErrorMessage("确认后的 AI 输入发生变化，请重新确认。");
                    blockers.add(item.getSourceFileName() + "（内容已变化）");
                }
            }
            if (!blockers.isEmpty()) {
                recomputeState(state);
                saveStateUnlocked(state);
                throw new BusinessException("还有文档未完成 AI 输入确认：" + join(blockers));
            }
            state.setStatus("RUNNING");
            state.setStartedAt(now());
            state.setFinishedAt(null);
            for (BatchSplitItemState item : state.getItems()) {
                item.setStatus("QUEUED");
                item.setStage("WAITING_AI_SPLIT");
                item.setProgressPercent(0);
                item.setStageMessage("等待进入 AI 智能拆分队列");
                item.setErrorMessage(null);
                item.setFinishedAt(null);
                projectIds.add(item.getProjectId());
            }
            saveStateUnlocked(state);
        }
        for (String projectId : projectIds) {
            submitExecution(batchId, projectId);
        }
        return get(batchId);
    }

    public BatchSplitJobResponse retryFailed(String batchId) {
        List<String> preparationIds = new ArrayList<String>();
        List<String> executionIds = new ArrayList<String>();
        synchronized (lockFor(batchId)) {
            BatchSplitState state = loadStateUnlocked(batchId);
            for (BatchSplitItemState item : state.getItems()) {
                if (!"FAILED".equals(item.getStatus())) {
                    continue;
                }
                item.setErrorMessage(null);
                item.setFinishedAt(null);
                if (StringUtils.hasText(item.getConfirmedAt()) && snapshotExists(batchId, item.getProjectId())) {
                    item.setStatus("QUEUED");
                    item.setStage("WAITING_AI_SPLIT");
                    item.setStageMessage("已手动恢复，等待进入 AI 智能拆分队列");
                    executionIds.add(item.getProjectId());
                } else {
                    item.setStatus("PARSE_QUEUED");
                    item.setStage("WAITING_PARSE");
                    item.setStageMessage("已手动恢复，等待进入文档解析队列");
                    preparationIds.add(item.getProjectId());
                }
            }
            if (preparationIds.isEmpty() && executionIds.isEmpty()) {
                throw new BusinessException("当前批次没有可重试的失败文档。");
            }
            if ("PROJECT".equals(state.getSplitLevel())
                    && "BLOCKED".equals(state.getProjectMaterialStatus())) {
                state.setProjectMaterialStatus("PENDING");
                state.setProjectMaterialMessage("失败需求正在重试；全部需求拆分成功后将自动生成项目级送审材料。");
            }
            state.setFinishedAt(null);
            recomputeState(state);
            saveStateUnlocked(state);
        }
        for (String projectId : preparationIds) {
            submitPreparation(batchId, projectId);
        }
        for (String projectId : executionIds) {
            submitExecution(batchId, projectId);
        }
        return get(batchId);
    }

    public BatchSplitJobResponse retryProjectMaterials(final String batchId) {
        synchronized (lockFor(batchId)) {
            BatchSplitState state = loadStateUnlocked(batchId);
            if (!"PROJECT".equals(state.getSplitLevel())) {
                throw new BusinessException("需求级批次不生成项目级送审材料。");
            }
            if (!"FAILED".equals(state.getProjectMaterialStatus())) {
                throw new BusinessException("当前项目级送审材料不需要重试。");
            }
            state.setProjectMaterialStatus("PENDING");
            state.setProjectMaterialMessage(null);
            state.setFinishedAt(null);
            recomputeState(state);
            saveStateUnlocked(state);
        }
        try {
            batchSplitExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    maybeGenerateProjectMaterials(batchId);
                }
            });
        } catch (RuntimeException ex) {
            failProjectMaterials(batchId, "项目级送审材料队列已满，请稍后重试：" + message(ex));
        }
        return get(batchId);
    }

    public Path projectMaterialFile(String batchId, String type) {
        BatchSplitState state = loadState(batchId);
        String path = state.getProjectMaterialFiles().get(type);
        if (!StringUtils.hasText(path)) {
            throw new BusinessException("项目级送审材料不存在：" + type);
        }
        Path file = Paths.get(path).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new BusinessException("项目级送审材料文件不存在：" + file.getFileName());
        }
        return file;
    }

    private void submitPreparation(final String batchId, final String projectId) {
        try {
            batchPreparationExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    prepareItem(batchId, projectId);
                }
            });
        } catch (RuntimeException ex) {
            failItem(batchId, projectId, "PARSE", "批量解析队列已满，请稍后重试：" + message(ex));
        }
    }

    private void prepareItem(String batchId, String projectId) {
        updateItem(batchId, projectId, new ItemMutation() {
            @Override
            public void apply(BatchSplitItemState item) {
                item.setStatus("PREPARING");
                item.setStage("PARSING");
                item.setStageMessage("正在解析文档并识别章节");
                item.setStartedAt(now());
                item.setAttempt(item.getAttempt() + 1);
            }
        });
        try {
            BatchSplitItemState current = findItem(loadState(batchId), projectId);
            if (current.isExistingRows()) {
                updateItem(batchId, projectId, new ItemMutation() {
                    @Override
                    public void apply(BatchSplitItemState item) {
                        item.setStatus("CONFIRMED");
                        item.setStage("INPUT_CONFIRMED");
                        item.setStageMessage("已导入拆分表，将执行统一智能校准");
                        item.setProgressPercent(0);
                        item.setConfirmedAt(now());
                        item.setLastCompletedStage("IMPORT");
                        item.setErrorMessage(null);
                    }
                });
                return;
            }
            ParseResponse parsed = documentParseService.parseProject(projectId);
            DocumentChapterSelectionResponse chapters = documentChapterSelectionService.get(projectId);
            final List<String> selectedIds = new ArrayList<String>();
            for (DocumentChapterItem chapter : chapters.getChapters()) {
                if (chapter.isSelected()) {
                    selectedIds.add(chapter.getId());
                }
            }
            if (!selectedIds.isEmpty()) {
                chapters = documentChapterSelectionService.apply(projectId, selectedIds);
            }
            final String warning = parsed.isRequirementRecognized()
                    ? null : firstText(parsed.getRequirementWarning(), parsed.getRequirementSuggestion());
            updateItem(batchId, projectId, new ItemMutation() {
                @Override
                public void apply(BatchSplitItemState item) {
                    item.setStatus("AWAITING_CONFIRMATION");
                    item.setStage("REVIEW_AI_INPUT");
                    item.setStageMessage("请确认送给 AI 的章节和正文");
                    item.setSelectedChapterIds(selectedIds);
                    item.setRequirementWarning(warning);
                    item.setLastCompletedStage("PARSE");
                    item.setErrorMessage(null);
                }
            });
        } catch (RuntimeException ex) {
            failItem(batchId, projectId, "PARSE", message(ex));
        }
    }

    private void submitExecution(final String batchId, final String projectId) {
        List<ScheduledExecution> ready;
        synchronized (executionScheduleLock) {
            String key = executionKey(batchId, projectId);
            if (!scheduledExecutions.add(key)) {
                return;
            }
            Deque<String> projects = pendingExecutions.get(batchId);
            if (projects == null) {
                projects = new ArrayDeque<String>();
                pendingExecutions.put(batchId, projects);
                pendingBatchOrder.addLast(batchId);
            }
            projects.addLast(projectId);
            ready = pollDispatchableExecutionsUnlocked();
        }
        dispatchExecutions(ready);
    }

    private List<ScheduledExecution> pollDispatchableExecutionsUnlocked() {
        List<ScheduledExecution> ready = new ArrayList<ScheduledExecution>();
        int remainingCandidates = pendingBatchOrder.size();
        while (activeExecutionCount < maxConcurrentExecutions
                && remainingCandidates > 0 && !pendingBatchOrder.isEmpty()) {
            String batchId = pendingBatchOrder.removeFirst();
            Deque<String> projects = pendingExecutions.get(batchId);
            if (projects == null || projects.isEmpty()) {
                pendingExecutions.remove(batchId);
                remainingCandidates--;
                continue;
            }
            int activeForBatch = activeExecutionsByBatch.containsKey(batchId)
                    ? activeExecutionsByBatch.get(batchId) : 0;
            if (activeForBatch >= maxConcurrentExecutionsPerBatch) {
                pendingBatchOrder.addLast(batchId);
                remainingCandidates--;
                continue;
            }

            String projectId = projects.removeFirst();
            if (projects.isEmpty()) {
                pendingExecutions.remove(batchId);
            } else {
                pendingBatchOrder.addLast(batchId);
            }
            activeExecutionCount++;
            activeExecutionsByBatch.put(batchId, activeForBatch + 1);
            ready.add(new ScheduledExecution(batchId, projectId));
            remainingCandidates = pendingBatchOrder.size();
        }
        return ready;
    }

    private void dispatchExecutions(List<ScheduledExecution> executions) {
        for (final ScheduledExecution execution : executions) {
            try {
                batchSplitExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            executeItem(execution.batchId, execution.projectId);
                        } finally {
                            executionFinished(execution);
                        }
                    }
                });
            } catch (RuntimeException ex) {
                List<ScheduledExecution> next = releaseExecutionSlot(execution);
                failItem(execution.batchId, execution.projectId, "AI_SPLIT",
                        "批量拆分队列已满，请稍后重试：" + message(ex));
                dispatchExecutions(next);
            }
        }
    }

    private void executionFinished(ScheduledExecution execution) {
        dispatchExecutions(releaseExecutionSlot(execution));
    }

    private List<ScheduledExecution> releaseExecutionSlot(ScheduledExecution execution) {
        synchronized (executionScheduleLock) {
            activeExecutionCount = Math.max(0, activeExecutionCount - 1);
            Integer activeForBatch = activeExecutionsByBatch.get(execution.batchId);
            if (activeForBatch == null || activeForBatch <= 1) {
                activeExecutionsByBatch.remove(execution.batchId);
            } else {
                activeExecutionsByBatch.put(execution.batchId, activeForBatch - 1);
            }
            scheduledExecutions.remove(executionKey(execution.batchId, execution.projectId));
            return pollDispatchableExecutionsUnlocked();
        }
    }

    private String executionKey(String batchId, String projectId) {
        return batchId + "/" + projectId;
    }

    private void executeItem(String batchId, String projectId) {
        updateItem(batchId, projectId, new ItemMutation() {
            @Override
            public void apply(BatchSplitItemState item) {
                item.setStatus("RUNNING");
                item.setAttempt(item.getAttempt() + 1);
                item.setStartedAt(now());
                item.setFinishedAt(null);
            }
        });
        try {
            BatchSplitItemState current = findItem(loadState(batchId), projectId);
            SmartSplitPipelineService.StageListener listener = new SmartSplitPipelineService.StageListener() {
                @Override
                public void onStage(final String stage, final int progressPercent, final String stageMessage) {
                    updateItem(batchId, projectId, new ItemMutation() {
                        @Override
                        public void apply(BatchSplitItemState item) {
                            item.setStatus("RUNNING");
                            item.setStage(stage);
                            item.setProgressPercent(progressPercent);
                            item.setStageMessage(stageMessage);
                        }
                    });
                }
            };
            final SmartSplitPipelineService.PipelineResult pipeline;
            if (current.isExistingRows()) {
                pipeline = smartSplitPipelineService.runExistingRows(projectId, listener);
            } else {
                String frozenInput = readInputSnapshot(batchId, projectId);
                requirementInputService.saveManualInput(projectId, frozenInput);
                pipeline = smartSplitPipelineService.runDocument(projectId, "", listener);
            }
            final RuleValidationResponse validation = pipeline.getValidation();
            updateItem(batchId, projectId, new ItemMutation() {
                @Override
                public void apply(BatchSplitItemState item) {
                    item.setStatus("COMPLETED");
                    item.setStage("COMPLETED");
                    item.setProgressPercent(100);
                    item.setStageMessage(pipeline.isPartial()
                            ? "智能拆分部分完成，已进入人工审核"
                            : "智能拆分、复用度调整和规则初审已完成");
                    item.setLastCompletedStage("RULE_VALIDATION");
                    item.setIssueCount(validation == null ? 0 : validation.getIssueCount());
                    item.setErrorCount(validation == null ? 0 : validation.getErrorCount());
                    item.setRiskCount(validation == null ? 0 : validation.getRiskCount());
                    item.setDeepSuggestionCount(pipeline.getDeepSuggestionCount());
                    item.setAppliedSuggestionCount(pipeline.getAppliedSuggestionCount());
                    item.setSkippedSuggestionCount(pipeline.getSkippedSuggestionCount());
                    item.setPartial(pipeline.isPartial());
                    item.setFinishedAt(now());
                    item.setErrorMessage(null);
                }
            });
            assignedProjects.remove(projectId, batchId);
            maybeGenerateProjectMaterials(batchId);
        } catch (RuntimeException ex) {
            failItem(batchId, projectId, currentStage(batchId, projectId), message(ex));
        }
    }

    private void failItem(String batchId, String projectId, final String stage, final String errorMessage) {
        updateItem(batchId, projectId, new ItemMutation() {
            @Override
            public void apply(BatchSplitItemState item) {
                item.setStatus("FAILED");
                item.setStage(stage);
                item.setErrorMessage(compactErrorMessage(errorMessage));
                item.setFinishedAt(now());
            }
        });
    }

    private String currentStage(String batchId, String projectId) {
        return findItem(loadState(batchId), projectId).getStage();
    }

    private void updateItem(String batchId, String projectId, ItemMutation mutation) {
        synchronized (lockFor(batchId)) {
            BatchSplitState state = loadStateUnlocked(batchId);
            BatchSplitItemState item = findItem(state, projectId);
            mutation.apply(item);
            recomputeState(state);
            saveStateUnlocked(state);
        }
    }

    private void maybeGenerateProjectMaterials(String batchId) {
        List<String> projectIds = new ArrayList<String>();
        synchronized (lockFor(batchId)) {
            BatchSplitState state = loadStateUnlocked(batchId);
            if (!"PROJECT".equals(state.getSplitLevel())
                    || !"PENDING".equals(state.getProjectMaterialStatus())) {
                return;
            }
            for (BatchSplitItemState item : state.getItems()) {
                if (!"COMPLETED".equals(item.getStatus())) {
                    return;
                }
                projectIds.add(item.getProjectId());
            }
            state.setProjectMaterialStatus("GENERATING");
            state.setProjectMaterialMessage("正在生成每个需求的送审材料，并汇总项目级附件 1–5。");
            state.setProjectMaterialCompletedAt(null);
            recomputeState(state);
            saveStateUnlocked(state);
        }

        try {
            ProjectMaterialExportService.ProjectMaterialBundle bundle =
                    projectMaterialExportService.export(batchId, projectIds);
            synchronized (lockFor(batchId)) {
                BatchSplitState state = loadStateUnlocked(batchId);
                state.getProjectMaterialFiles().clear();
                for (java.util.Map.Entry<String, Path> entry : bundle.getFiles().entrySet()) {
                    state.getProjectMaterialFiles().put(entry.getKey(),
                            entry.getValue().toAbsolutePath().normalize().toString());
                }
                state.setProjectMaterialStatus("COMPLETED");
                state.setProjectMaterialMessage("项目级送审材料已生成，共包含附件 1–5。");
                state.setProjectMaterialCompletedAt(now());
                state.setProjectMaterialTotalCfp(bundle.getTotalCfp());
                state.setProjectMaterialRequirementCount(bundle.getRequirementCount());
                recomputeState(state);
                saveStateUnlocked(state);
            }
        } catch (RuntimeException ex) {
            failProjectMaterials(batchId, message(ex));
        }
    }

    private void failProjectMaterials(String batchId, String errorMessage) {
        synchronized (lockFor(batchId)) {
            BatchSplitState state = loadStateUnlocked(batchId);
            state.setProjectMaterialStatus("FAILED");
            state.setProjectMaterialMessage(errorMessage);
            recomputeState(state);
            saveStateUnlocked(state);
        }
    }

    private void recomputeState(BatchSplitState state) {
        boolean activeExecution = false;
        boolean preparing = false;
        boolean failed = false;
        boolean awaiting = false;
        boolean allConfirmed = !state.getItems().isEmpty();
        boolean allCompleted = !state.getItems().isEmpty();
        int failedCount = 0;
        int interruptedCount = 0;
        for (BatchSplitItemState item : state.getItems()) {
            String status = item.getStatus();
            activeExecution |= "QUEUED".equals(status) || "RUNNING".equals(status);
            preparing |= "PARSE_QUEUED".equals(status) || "PREPARING".equals(status);
            failed |= "FAILED".equals(status);
            if ("FAILED".equals(status)) {
                failedCount++;
                if ("RESTART_INTERRUPTED".equals(item.getStage())) {
                    interruptedCount++;
                }
            }
            awaiting |= "AWAITING_CONFIRMATION".equals(status);
            allConfirmed &= "CONFIRMED".equals(status);
            allCompleted &= "COMPLETED".equals(status);
        }
        if ("PROJECT".equals(state.getSplitLevel()) && failedCount > 0
                && !"GENERATING".equals(state.getProjectMaterialStatus())
                && !"COMPLETED".equals(state.getProjectMaterialStatus())) {
            state.setProjectMaterialStatus("BLOCKED");
            state.setProjectMaterialMessage(interruptedCount > 0
                    ? "服务重启已停止 " + interruptedCount
                    + " 个未完成需求，项目级送审材料保持暂停；手动恢复并全部成功后才会生成。"
                    : "有 " + failedCount
                    + " 个需求拆分失败，项目级送审材料已暂停；重试失败项并全部成功后将自动生成。");
            state.setProjectMaterialCompletedAt(null);
        }
        if (allCompleted) {
            if ("PROJECT".equals(state.getSplitLevel())) {
                if ("COMPLETED".equals(state.getProjectMaterialStatus())) {
                    state.setStatus("COMPLETED");
                    state.setFinishedAt(now());
                } else if ("GENERATING".equals(state.getProjectMaterialStatus())) {
                    state.setStatus("GENERATING_MATERIALS");
                    state.setFinishedAt(null);
                } else if ("FAILED".equals(state.getProjectMaterialStatus())) {
                    state.setStatus("MATERIAL_FAILED");
                    state.setFinishedAt(now());
                } else {
                    state.setStatus("MATERIAL_PENDING");
                    state.setFinishedAt(null);
                }
            } else {
                state.setStatus("COMPLETED");
                state.setFinishedAt(now());
            }
        } else if (activeExecution) {
            state.setStatus("RUNNING");
            state.setFinishedAt(null);
        } else if (preparing) {
            state.setStatus("PREPARING");
            state.setFinishedAt(null);
        } else if (allConfirmed) {
            state.setStatus("READY");
            state.setFinishedAt(null);
        } else if (failed && StringUtils.hasText(state.getStartedAt())) {
            state.setStatus("PARTIAL_COMPLETED");
            state.setFinishedAt(now());
        } else if (failed || awaiting) {
            state.setStatus("AWAITING_CONFIRMATION");
            state.setFinishedAt(null);
        }
        state.setUpdatedAt(now());
    }

    private boolean allItemsCompleted(BatchSplitState state) {
        if (state.getItems().isEmpty()) {
            return false;
        }
        for (BatchSplitItemState item : state.getItems()) {
            if (!"COMPLETED".equals(item.getStatus())) {
                return false;
            }
        }
        return true;
    }

    private boolean isExecutionStarted(BatchSplitState state) {
        return StringUtils.hasText(state.getStartedAt()) || "RUNNING".equals(state.getStatus())
                || "COMPLETED".equals(state.getStatus()) || "PARTIAL_COMPLETED".equals(state.getStatus())
                || "MATERIAL_PENDING".equals(state.getStatus()) || "GENERATING_MATERIALS".equals(state.getStatus())
                || "MATERIAL_FAILED".equals(state.getStatus());
    }

    private List<String> normalizeProjectIds(List<String> projectIds) {
        Set<String> unique = new LinkedHashSet<String>();
        if (projectIds != null) {
            for (String projectId : projectIds) {
                String value = trim(projectId);
                if (StringUtils.hasText(value)) {
                    if (!value.matches("P[0-9A-Z]+")) {
                        throw new BusinessException("无效的项目编号：" + value);
                    }
                    unique.add(value);
                }
            }
        }
        return new ArrayList<String>(unique);
    }

    private void validateBatchProject(ProjectMetadata metadata) {
        if (metadata == null || !StringUtils.hasText(metadata.getId())) {
            throw new BusinessException("项目不存在。");
        }
        if (!"doc".equalsIgnoreCase(metadata.getFileType())
                && !"docx".equalsIgnoreCase(metadata.getFileType())
                && !"xls".equalsIgnoreCase(metadata.getFileType())
                && !"xlsx".equalsIgnoreCase(metadata.getFileType())) {
            throw new BusinessException("批量拆分只支持 Word 需求文档或已导入拆分表：" + metadata.getSourceFileName());
        }
    }

    private boolean isExistingRowsProject(ProjectMetadata metadata) {
        String type = metadata == null ? "" : trim(metadata.getFileType()).toLowerCase(Locale.ROOT);
        return "xls".equals(type) || "xlsx".equals(type);
    }

    private String normalizeSplitLevel(String splitLevel) {
        return "PROJECT".equalsIgnoreCase(trim(splitLevel)) ? "PROJECT" : "REQUIREMENT";
    }

    private BatchSplitItemState findItem(BatchSplitState state, String projectId) {
        for (BatchSplitItemState item : state.getItems()) {
            if (item.getProjectId().equals(projectId)) {
                return item;
            }
        }
        throw new BusinessException("批次中不存在该文档：" + projectId);
    }

    private String readCurrentInput(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        if (!StringUtils.hasText(metadata.getRequirementDetailPath())) {
            return "";
        }
        Path path = Paths.get(metadata.getRequirementDetailPath());
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            throw new BusinessException("读取 AI 输入失败：" + metadata.getSourceFileName(), ex);
        }
    }

    private void writeInputSnapshot(String batchId, String projectId, String input) {
        Path path = inputSnapshotPath(batchId, projectId);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, input.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BusinessException("保存批量拆分 AI 输入快照失败。", ex);
        }
    }

    private String readInputSnapshot(String batchId, String projectId) {
        Path path = inputSnapshotPath(batchId, projectId);
        if (!Files.exists(path)) {
            throw new BusinessException("已确认的 AI 输入快照不存在，请重新确认该文档。");
        }
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BusinessException("读取批量拆分 AI 输入快照失败。", ex);
        }
    }

    private boolean snapshotExists(String batchId, String projectId) {
        return Files.exists(inputSnapshotPath(batchId, projectId));
    }

    private Path inputSnapshotPath(String batchId, String projectId) {
        return batchDir(batchId).resolve("inputs").resolve(projectId + ".txt");
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private BatchSplitJobResponse toResponse(BatchSplitState state) {
        BatchSplitJobResponse response = new BatchSplitJobResponse();
        response.setBatchId(state.getBatchId());
        response.setSplitLevel(normalizeSplitLevel(state.getSplitLevel()));
        response.setStatus(state.getStatus());
        response.setCreatedAt(state.getCreatedAt());
        response.setUpdatedAt(state.getUpdatedAt());
        response.setStartedAt(state.getStartedAt());
        response.setFinishedAt(state.getFinishedAt());
        response.setTotal(state.getItems().size());
        response.setProjectMaterialStatus(StringUtils.hasText(state.getProjectMaterialStatus())
                ? state.getProjectMaterialStatus() : "NOT_REQUIRED");
        response.setProjectMaterialMessage(state.getProjectMaterialMessage());
        response.setProjectMaterialCompletedAt(state.getProjectMaterialCompletedAt());
        response.setProjectMaterialTotalCfp(state.getProjectMaterialTotalCfp());
        response.setProjectMaterialRequirementCount(state.getProjectMaterialRequirementCount());
        List<ProjectMaterialFileResponse> materialFiles = new ArrayList<ProjectMaterialFileResponse>();
        for (java.util.Map.Entry<String, String> entry : state.getProjectMaterialFiles().entrySet()) {
            ProjectMaterialFileResponse file = new ProjectMaterialFileResponse();
            file.setType(entry.getKey());
            Path path = Paths.get(entry.getValue());
            file.setName(path.getFileName().toString());
            file.setPath(entry.getValue());
            file.setDownloadUrl("/api/projects/batch-split/" + state.getBatchId()
                    + "/materials/" + entry.getKey() + "/download");
            materialFiles.add(file);
        }
        response.setProjectMaterialFiles(materialFiles);
        List<BatchSplitItemResponse> items = new ArrayList<BatchSplitItemResponse>();
        for (BatchSplitItemState item : state.getItems()) {
            BatchSplitItemResponse target = new BatchSplitItemResponse();
            target.setProjectId(item.getProjectId());
            target.setProjectName(item.getProjectName());
            target.setSourceFileName(item.getSourceFileName());
            target.setStatus(item.getStatus());
            target.setStage(item.getStage());
            target.setProgressPercent(item.getProgressPercent());
            target.setStageMessage(item.getStageMessage());
            target.setExistingRows(item.isExistingRows());
            target.setRequirementWarning(item.getRequirementWarning());
            target.setSelectedChapterIds(new ArrayList<String>(item.getSelectedChapterIds()));
            target.setConfirmedAt(item.getConfirmedAt());
            target.setInputLength(item.getInputLength());
            target.setLastCompletedStage(item.getLastCompletedStage());
            target.setErrorMessage(item.getErrorMessage());
            target.setStartedAt(item.getStartedAt());
            target.setFinishedAt(item.getFinishedAt());
            target.setAttempt(item.getAttempt());
            target.setIssueCount(item.getIssueCount());
            target.setErrorCount(item.getErrorCount());
            target.setRiskCount(item.getRiskCount());
            target.setDeepSuggestionCount(item.getDeepSuggestionCount());
            target.setAppliedSuggestionCount(item.getAppliedSuggestionCount());
            target.setSkippedSuggestionCount(item.getSkippedSuggestionCount());
            target.setPartial(item.isPartial());
            items.add(target);
            countStatus(response, item.getStatus());
        }
        response.setItems(items);
        return response;
    }

    private void countStatus(BatchSplitJobResponse response, String status) {
        if ("PARSE_QUEUED".equals(status)) response.setParseQueued(response.getParseQueued() + 1);
        else if ("PREPARING".equals(status)) response.setPreparing(response.getPreparing() + 1);
        else if ("AWAITING_CONFIRMATION".equals(status)) response.setAwaitingConfirmation(response.getAwaitingConfirmation() + 1);
        else if ("CONFIRMED".equals(status)) response.setConfirmed(response.getConfirmed() + 1);
        else if ("QUEUED".equals(status)) response.setQueued(response.getQueued() + 1);
        else if ("RUNNING".equals(status)) response.setRunning(response.getRunning() + 1);
        else if ("COMPLETED".equals(status)) response.setCompleted(response.getCompleted() + 1);
        else if ("FAILED".equals(status)) response.setFailed(response.getFailed() + 1);
    }

    private BatchSplitState loadState(String batchId) {
        synchronized (lockFor(batchId)) {
            return loadStateUnlocked(batchId);
        }
    }

    private BatchSplitState loadStateUnlocked(String batchId) {
        Path path = statePath(batchId);
        if (!Files.exists(path)) {
            throw new BusinessException("批量拆分任务不存在：" + batchId);
        }
        try {
            return objectMapper.readValue(path.toFile(), BatchSplitState.class);
        } catch (IOException ex) {
            throw new BusinessException("读取批量拆分任务失败：" + batchId, ex);
        }
    }

    private void saveState(BatchSplitState state) {
        synchronized (lockFor(state.getBatchId())) {
            saveStateUnlocked(state);
        }
    }

    private void saveStateUnlocked(BatchSplitState state) {
        Path directory = batchDir(state.getBatchId());
        Path target = directory.resolve("state.json");
        Path temporary = directory.resolve("state-" + UUID.randomUUID().toString() + ".tmp");
        state.setUpdatedAt(now());
        try {
            Files.createDirectories(directory);
            objectMapper.writeValue(temporary.toFile(), state);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original save failure.
            }
            throw new BusinessException("保存批量拆分任务失败：" + state.getBatchId(), ex);
        }
    }

    private Path statePath(String batchId) {
        return batchDir(batchId).resolve("state.json");
    }

    private Path batchDir(String batchId) {
        if (!StringUtils.hasText(batchId) || !batchId.matches("BS[0-9A-Z]+")) {
            throw new BusinessException("无效的批量拆分任务编号。");
        }
        return batchBaseDir.resolve(batchId);
    }

    private Object lockFor(String batchId) {
        Object current = batchLocks.get(batchId);
        if (current != null) {
            return current;
        }
        Object created = new Object();
        Object raced = batchLocks.putIfAbsent(batchId, created);
        return raced == null ? created : raced;
    }

    private String generateBatchId() {
        return "BS" + LocalDateTime.now().format(ID_TIME_FORMATTER)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private String statusText(String status) {
        if ("PARSE_QUEUED".equals(status)) return "等待解析";
        if ("PREPARING".equals(status)) return "解析中";
        if ("AWAITING_CONFIRMATION".equals(status)) return "待确认";
        if ("FAILED".equals(status)) return "失败";
        return status == null ? "未知状态" : status;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : (StringUtils.hasText(second) ? second : null);
    }

    private String message(Throwable throwable) {
        return StringUtils.hasText(throwable.getMessage())
                ? throwable.getMessage() : throwable.getClass().getSimpleName();
    }

    private String compactErrorMessage(String value) {
        String text = value == null ? "未知错误" : value.replaceAll("\\s+", " ").trim();
        if (text.length() <= 1000) {
            return text;
        }
        return text.substring(0, 700) + " ...[错误详情已截短]... "
                + text.substring(text.length() - 200);
    }

    private String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append("、");
            result.append(value);
        }
        return result.toString();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String now() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }

    private static final class ScheduledExecution {
        private final String batchId;
        private final String projectId;

        private ScheduledExecution(String batchId, String projectId) {
            this.batchId = batchId;
            this.projectId = projectId;
        }
    }

    private interface ItemMutation {
        void apply(BatchSplitItemState item);
    }

    public static class BatchSplitState {
        private String batchId;
        private String splitLevel = "REQUIREMENT";
        private String status;
        private String createdAt;
        private String updatedAt;
        private String startedAt;
        private String finishedAt;
        private String projectMaterialStatus = "NOT_REQUIRED";
        private String projectMaterialMessage;
        private String projectMaterialCompletedAt;
        private double projectMaterialTotalCfp;
        private int projectMaterialRequirementCount;
        private Map<String, String> projectMaterialFiles = new LinkedHashMap<String, String>();
        private List<BatchSplitItemState> items = new ArrayList<BatchSplitItemState>();

        public String getBatchId() { return batchId; }
        public void setBatchId(String batchId) { this.batchId = batchId; }
        public String getSplitLevel() { return splitLevel; }
        public void setSplitLevel(String splitLevel) { this.splitLevel = splitLevel; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
        public String getStartedAt() { return startedAt; }
        public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
        public String getFinishedAt() { return finishedAt; }
        public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
        public String getProjectMaterialStatus() { return projectMaterialStatus; }
        public void setProjectMaterialStatus(String projectMaterialStatus) { this.projectMaterialStatus = projectMaterialStatus; }
        public String getProjectMaterialMessage() { return projectMaterialMessage; }
        public void setProjectMaterialMessage(String projectMaterialMessage) { this.projectMaterialMessage = projectMaterialMessage; }
        public String getProjectMaterialCompletedAt() { return projectMaterialCompletedAt; }
        public void setProjectMaterialCompletedAt(String projectMaterialCompletedAt) { this.projectMaterialCompletedAt = projectMaterialCompletedAt; }
        public double getProjectMaterialTotalCfp() { return projectMaterialTotalCfp; }
        public void setProjectMaterialTotalCfp(double projectMaterialTotalCfp) { this.projectMaterialTotalCfp = projectMaterialTotalCfp; }
        public int getProjectMaterialRequirementCount() { return projectMaterialRequirementCount; }
        public void setProjectMaterialRequirementCount(int projectMaterialRequirementCount) { this.projectMaterialRequirementCount = projectMaterialRequirementCount; }
        public Map<String, String> getProjectMaterialFiles() {
            if (projectMaterialFiles == null) projectMaterialFiles = new LinkedHashMap<String, String>();
            return projectMaterialFiles;
        }
        public void setProjectMaterialFiles(Map<String, String> projectMaterialFiles) {
            this.projectMaterialFiles = projectMaterialFiles == null
                    ? new LinkedHashMap<String, String>() : projectMaterialFiles;
        }
        public List<BatchSplitItemState> getItems() { return items; }
        public void setItems(List<BatchSplitItemState> items) { this.items = items; }
    }

    public static class BatchSplitItemState {
        private String projectId;
        private String projectName;
        private String sourceFileName;
        private String status;
        private String stage;
        private int progressPercent;
        private String stageMessage;
        private boolean existingRows;
        private String requirementWarning;
        private List<String> selectedChapterIds = new ArrayList<String>();
        private String confirmedAt;
        private String inputHash;
        private int inputLength;
        private String lastCompletedStage;
        private String errorMessage;
        private String startedAt;
        private String finishedAt;
        private int attempt;
        private int issueCount;
        private int errorCount;
        private int riskCount;
        private int deepSuggestionCount;
        private int appliedSuggestionCount;
        private int skippedSuggestionCount;
        private boolean partial;

        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getProjectName() { return projectName; }
        public void setProjectName(String projectName) { this.projectName = projectName; }
        public String getSourceFileName() { return sourceFileName; }
        public void setSourceFileName(String sourceFileName) { this.sourceFileName = sourceFileName; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getStage() { return stage; }
        public void setStage(String stage) { this.stage = stage; }
        public int getProgressPercent() { return progressPercent; }
        public void setProgressPercent(int progressPercent) { this.progressPercent = progressPercent; }
        public String getStageMessage() { return stageMessage; }
        public void setStageMessage(String stageMessage) { this.stageMessage = stageMessage; }
        public boolean isExistingRows() { return existingRows; }
        public void setExistingRows(boolean existingRows) { this.existingRows = existingRows; }
        public String getRequirementWarning() { return requirementWarning; }
        public void setRequirementWarning(String requirementWarning) { this.requirementWarning = requirementWarning; }
        public List<String> getSelectedChapterIds() { return selectedChapterIds; }
        public void setSelectedChapterIds(List<String> selectedChapterIds) { this.selectedChapterIds = selectedChapterIds; }
        public String getConfirmedAt() { return confirmedAt; }
        public void setConfirmedAt(String confirmedAt) { this.confirmedAt = confirmedAt; }
        public String getInputHash() { return inputHash; }
        public void setInputHash(String inputHash) { this.inputHash = inputHash; }
        public int getInputLength() { return inputLength; }
        public void setInputLength(int inputLength) { this.inputLength = inputLength; }
        public String getLastCompletedStage() { return lastCompletedStage; }
        public void setLastCompletedStage(String lastCompletedStage) { this.lastCompletedStage = lastCompletedStage; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getStartedAt() { return startedAt; }
        public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
        public String getFinishedAt() { return finishedAt; }
        public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
        public int getAttempt() { return attempt; }
        public void setAttempt(int attempt) { this.attempt = attempt; }
        public int getIssueCount() { return issueCount; }
        public void setIssueCount(int issueCount) { this.issueCount = issueCount; }
        public int getErrorCount() { return errorCount; }
        public void setErrorCount(int errorCount) { this.errorCount = errorCount; }
        public int getRiskCount() { return riskCount; }
        public void setRiskCount(int riskCount) { this.riskCount = riskCount; }
        public int getDeepSuggestionCount() { return deepSuggestionCount; }
        public void setDeepSuggestionCount(int deepSuggestionCount) { this.deepSuggestionCount = deepSuggestionCount; }
        public int getAppliedSuggestionCount() { return appliedSuggestionCount; }
        public void setAppliedSuggestionCount(int appliedSuggestionCount) { this.appliedSuggestionCount = appliedSuggestionCount; }
        public int getSkippedSuggestionCount() { return skippedSuggestionCount; }
        public void setSkippedSuggestionCount(int skippedSuggestionCount) { this.skippedSuggestionCount = skippedSuggestionCount; }
        public boolean isPartial() { return partial; }
        public void setPartial(boolean partial) { this.partial = partial; }
    }
}
