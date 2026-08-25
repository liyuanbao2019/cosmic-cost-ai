package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.BatchSplitConfirmRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitCreateRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitItemResponse;
import com.li.jc.cosmiccostai.dto.BatchSplitItemsRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitJobResponse;
import com.li.jc.cosmiccostai.dto.BatchSplitSummaryItemResponse;
import com.li.jc.cosmiccostai.dto.BatchSplitSummaryResponse;
import com.li.jc.cosmiccostai.dto.AiPartitionProgressItem;
import com.li.jc.cosmiccostai.dto.DocumentChapterItem;
import com.li.jc.cosmiccostai.dto.DocumentChapterSelectionResponse;
import com.li.jc.cosmiccostai.dto.ParseResponse;
import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.dto.ProjectMaterialFileResponse;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.Comparator;
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
import java.util.stream.Stream;

/**
 * Coordinates the two-phase multi-document split workflow. Preparation only parses
 * documents. AI work is not scheduled until every item has an immutable, confirmed
 * input snapshot.
 */
@Service
public class BatchSplitService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchSplitService.class);

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
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Object> batchLocks = new ConcurrentHashMap<String, Object>();
    private final ConcurrentMap<String, String> assignedProjects = new ConcurrentHashMap<String, String>();
    private final Object executionScheduleLock = new Object();
    private final Map<String, Deque<String>> pendingExecutions = new LinkedHashMap<String, Deque<String>>();
    private final Deque<String> pendingBatchOrder = new ArrayDeque<String>();
    private final Set<String> scheduledExecutions = new HashSet<String>();
    private final Set<String> cancelledExecutions = ConcurrentHashMap.newKeySet();
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
                             @Value("${cosmic.ai.batch-split.worker-count:1000}") int maxConcurrentExecutions,
                             @Value("${cosmic.ai.batch-split.max-concurrency-per-batch:1000}") int maxConcurrentExecutionsPerBatch,
                             @Value("${cosmic.storage.base-dir:storage}") String storageBaseDir) {
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
                try {
                    synchronized (lockFor(batchId)) {
                        BatchSplitState state;
                        try {
                            state = loadStateUnlocked(batchId);
                        } catch (BusinessException ex) {
                            continue;
                        }
                        boolean stateChanged = false;
                        if ("PROJECT".equals(normalizeSplitLevel(state.getSplitLevel()))
                                && !StringUtils.hasText(state.getProjectGroupId())) {
                            state.setProjectGroupId(generateProjectGroupId());
                            state.setProjectGroupName(resolveProjectGroupName(state, null));
                            if ("COMPLETED".equals(state.getProjectMaterialStatus())
                                    && state.getProjectMaterialVersion() <= 0) {
                                state.setProjectMaterialVersion(1);
                            }
                            stateChanged = true;
                        }
                        if ("GENERATING".equals(state.getProjectMaterialStatus())) {
                            state.setProjectMaterialStatus("FAILED");
                            state.setProjectMaterialMessage("服务重启已停止项目级送审材料生成，未自动恢复；请手动点击重试。");
                            stateChanged = true;
                        }
                        if ("GENERATING".equals(state.getRequirementMaterialStatus())) {
                            state.setRequirementMaterialStatus("FAILED");
                            state.setRequirementMaterialMessage("服务重启已停止单需求送审材料总包生成，未自动恢复；请手动重新生成。");
                            stateChanged = true;
                        }
                        for (BatchSplitItemState item : state.getItems()) {
                            if (!projectMetadataExists(item.getProjectId())) {
                                assignedProjects.remove(item.getProjectId(), batchId);
                                item.setStatus("FAILED");
                                item.setStage("PROJECT_MISSING");
                                item.setStageMessage("需求项目已被删除，无法继续处理");
                                item.setErrorMessage("本地需求项目不存在：" + item.getProjectId()
                                        + "。其他需求和服务启动不受影响；如仍需该需求，请重新上传后加入项目。");
                                item.setFinishedAt(now());
                                stateChanged = true;
                                continue;
                            }
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
                        if (StringUtils.hasText(state.getProjectGroupId())) {
                            associateProjectsForRecovery(state);
                        }
                    }
                } catch (RuntimeException ex) {
                    LOGGER.error("Skipping unreadable persisted batch during startup recovery. batchId={}",
                            batchId, ex);
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
        String batchId = generateBatchId();
        BatchSplitState state = new BatchSplitState();
        state.setBatchId(batchId);
        state.setSplitLevel(normalizeSplitLevel(request == null ? null : request.getSplitLevel()));
        state.setReuseDegreeAdjustmentEnabled(request == null
                || request.isReuseDegreeAdjustmentEnabled());
        if ("PROJECT".equals(state.getSplitLevel())) {
            state.setProjectGroupId(generateProjectGroupId());
            state.setProjectGroupName(resolveProjectGroupName(null,
                    request == null ? null : request.getProjectGroupName(), projectIds));
            assertProjectGroupProjectsAvailable(projectIds, batchId);
        }
        state.setProjectMaterialStatus("PROJECT".equals(state.getSplitLevel()) ? "PENDING" : "NOT_REQUIRED");
        state.setStatus("PREPARING");
        state.setCreatedAt(now());
        state.setUpdatedAt(state.getCreatedAt());

        List<String> reserved = new ArrayList<String>();
        List<String> preparationIds = new ArrayList<String>();
        try {
            for (String projectId : projectIds) {
                ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
                validateBatchProject(metadata);
                validateProjectGroupMembership(state, metadata);
                String existingBatch = assignedProjects.get(projectId);
                if (existingBatch != null && !batchId.equals(existingBatch)) {
                    throw new BusinessException("文档正在另一个批次中处理：" + metadata.getSourceFileName());
                }
                boolean reuse = request != null && request.isReuseCompletedResults()
                        && hasReusableSplitResult(metadata);
                BatchSplitItemState item = newItemState(metadata, reuse);
                state.getItems().add(item);
                if (!reuse) {
                    String raced = assignedProjects.putIfAbsent(projectId, batchId);
                    if (raced != null && !batchId.equals(raced)) {
                        throw new BusinessException("文档正在另一个批次中处理：" + metadata.getSourceFileName());
                    }
                    reserved.add(projectId);
                    preparationIds.add(projectId);
                }
            }
            associateProjects(state);
            recomputeState(state);
            saveState(state);
        } catch (RuntimeException ex) {
            for (String projectId : reserved) {
                assignedProjects.remove(projectId, batchId);
            }
            throw ex;
        }

        for (String projectId : preparationIds) {
            submitPreparation(batchId, projectId);
        }
        return get(batchId);
    }

    public BatchSplitJobResponse createProjectGroup(BatchSplitCreateRequest request) {
        BatchSplitCreateRequest normalized = request == null ? new BatchSplitCreateRequest() : request;
        normalized.setSplitLevel("PROJECT");
        normalized.setReuseCompletedResults(true);
        return create(normalized);
    }

    private void associateProjects(BatchSplitState state) {
        for (BatchSplitItemState item : state.getItems()) {
            ProjectMetadata metadata = fileStorageService.loadMetadata(item.getProjectId());
            if (StringUtils.hasText(state.getProjectGroupId())) {
                metadata.setSplitLevel("REQUIREMENT");
                metadata.setBatchId(null);
            } else {
                metadata.setSplitLevel(normalizeSplitLevel(state.getSplitLevel()));
                metadata.setBatchId(state.getBatchId());
            }
            fileStorageService.saveMetadata(metadata);
        }
    }

    private void associateProjectsForRecovery(BatchSplitState state) {
        for (BatchSplitItemState item : state.getItems()) {
            try {
                ProjectMetadata metadata = fileStorageService.loadMetadata(item.getProjectId());
                metadata.setSplitLevel("REQUIREMENT");
                metadata.setBatchId(null);
                fileStorageService.saveMetadata(metadata);
            } catch (RuntimeException ex) {
                LOGGER.warn("Persisted batch member is unavailable; association migration skipped. batchId={}, projectId={}",
                        state.getBatchId(), item.getProjectId());
            }
        }
    }

    public List<BatchSplitJobResponse> listProjectGroups() {
        List<BatchSplitJobResponse> groups = new ArrayList<BatchSplitJobResponse>();
        if (!Files.exists(batchBaseDir)) {
            return groups;
        }
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(batchBaseDir, "BS*")) {
            for (Path directory : directories) {
                if (!Files.isRegularFile(directory.resolve("state.json"))) {
                    continue;
                }
                try {
                    BatchSplitState state = loadState(directory.getFileName().toString());
                    if ("PROJECT".equals(normalizeSplitLevel(state.getSplitLevel()))
                            && StringUtils.hasText(state.getProjectGroupId())) {
                        groups.add(toResponse(state));
                    }
                } catch (BusinessException ignored) {
                    // Keep other readable project groups visible.
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("读取项目级项目列表失败。", ex);
        }
        Collections.sort(groups, new Comparator<BatchSplitJobResponse>() {
            @Override
            public int compare(BatchSplitJobResponse left, BatchSplitJobResponse right) {
                return trim(right.getUpdatedAt()).compareTo(trim(left.getUpdatedAt()));
            }
        });
        return groups;
    }

    public BatchSplitJobResponse getProjectGroup(String projectGroupId) {
        String expected = normalizeProjectGroupId(projectGroupId);
        for (BatchSplitJobResponse group : listProjectGroups()) {
            if (expected.equals(group.getProjectGroupId())) {
                return group;
            }
        }
        throw new BusinessException("项目级项目不存在：" + expected);
    }

    public void deleteProjectGroup(String projectGroupId) {
        deleteProjectGroups(Collections.singletonList(projectGroupId));
    }

    public int deleteProjectGroups(List<String> projectGroupIds) {
        Set<String> unique = new LinkedHashSet<String>();
        if (projectGroupIds != null) {
            for (String projectGroupId : projectGroupIds) {
                unique.add(normalizeProjectGroupId(projectGroupId));
            }
        }
        if (unique.isEmpty()) {
            throw new BusinessException("请先选择要删除的项目级项目。");
        }
        Map<String, BatchSplitState> states = new LinkedHashMap<String, BatchSplitState>();
        Set<String> deletingBatchIds = new HashSet<String>();
        Set<String> deletingProjectIds = new LinkedHashSet<String>();
        for (String projectGroupId : unique) {
            BatchSplitState state = findProjectGroupState(projectGroupId);
            assertProjectGroupDeletable(state);
            states.put(projectGroupId, state);
            deletingBatchIds.add(state.getBatchId());
            for (BatchSplitItemState item : state.getItems()) {
                deletingProjectIds.add(item.getProjectId());
            }
        }
        assertRequirementsNotSharedWithRetainedGroups(deletingBatchIds, deletingProjectIds);

        for (Map.Entry<String, BatchSplitState> entry : states.entrySet()) {
            BatchSplitState located = entry.getValue();
            String batchId = located.getBatchId();
            synchronized (lockFor(batchId)) {
                BatchSplitState state = loadStateUnlocked(batchId);
                if (!entry.getKey().equals(state.getProjectGroupId())) {
                    throw new BusinessException("项目级项目状态已变化，请刷新后重试。");
                }
                assertProjectGroupDeletable(state);
                deleteProjectGroupData(state);
            }
            batchLocks.remove(batchId);
        }
        return states.size();
    }

    private void deleteProjectGroupData(BatchSplitState state) {
        List<String> projectIds = new ArrayList<String>();
        for (BatchSplitItemState item : state.getItems()) {
            projectIds.add(item.getProjectId());
        }
        projectMaterialExportService.deleteMaterialVersions(state.getProjectGroupId(), state.getBatchId());
        projectMaterialExportService.deleteRequirementArchive(state.getBatchId());
        projectMaterialExportService.deleteRequirementOutputs(projectIds);
        for (String projectId : projectIds) {
            deleteRequirementProjectDirectory(projectId);
            assignedProjects.remove(projectId, state.getBatchId());
        }
        deleteBatchDirectory(state.getBatchId());
    }

    private void assertRequirementsNotSharedWithRetainedGroups(Set<String> deletingBatchIds,
                                                                Set<String> deletingProjectIds) {
        if (!Files.exists(batchBaseDir) || deletingProjectIds.isEmpty()) return;
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(batchBaseDir, "BS*")) {
            for (Path directory : directories) {
                String batchId = directory.getFileName().toString();
                if (deletingBatchIds.contains(batchId) || !Files.isRegularFile(directory.resolve("state.json"))) {
                    continue;
                }
                BatchSplitState state;
                try {
                    state = loadState(batchId);
                } catch (BusinessException ignored) {
                    continue;
                }
                if (!"PROJECT".equals(normalizeSplitLevel(state.getSplitLevel()))
                        || !StringUtils.hasText(state.getProjectGroupId())) continue;
                for (BatchSplitItemState item : state.getItems()) {
                    if (deletingProjectIds.contains(item.getProjectId())) {
                        throw new BusinessException("需求“" + item.getSourceFileName() + "”同时属于未删除的项目级项目“"
                                + state.getProjectGroupName() + "”（" + state.getProjectGroupId()
                                + "）。请同时勾选该项目级项目后再删除，避免留下失效项目。");
                    }
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("检查项目级项目关联关系失败。", ex);
        }
    }

    private void deleteRequirementProjectDirectory(String projectId) {
        String safeProjectId = trim(projectId).toUpperCase(Locale.ROOT);
        if (!safeProjectId.matches("P[0-9A-Z]+")) {
            throw new BusinessException("关联需求编号非法，已拒绝级联删除：" + projectId);
        }
        Path root = batchBaseDir.getParent().resolve("projects").toAbsolutePath().normalize();
        Path target = root.resolve(safeProjectId).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new BusinessException("关联需求删除路径非法，已拒绝操作。");
        }
        deleteDirectoryTree(target, "删除关联需求失败：" + safeProjectId);
    }

    private void assertProjectGroupProjectsAvailable(List<String> projectIds, String targetBatchId) {
        if (!Files.exists(batchBaseDir) || projectIds == null || projectIds.isEmpty()) return;
        Set<String> requested = new HashSet<String>(projectIds);
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(batchBaseDir, "BS*")) {
            for (Path directory : directories) {
                String batchId = directory.getFileName().toString();
                if (batchId.equals(targetBatchId) || !Files.isRegularFile(directory.resolve("state.json"))) continue;
                BatchSplitState state;
                try {
                    state = loadState(batchId);
                } catch (BusinessException ignored) {
                    continue;
                }
                if (!"PROJECT".equals(normalizeSplitLevel(state.getSplitLevel()))
                        || !StringUtils.hasText(state.getProjectGroupId())) continue;
                for (BatchSplitItemState item : state.getItems()) {
                    if (requested.contains(item.getProjectId())) {
                        throw new BusinessException("需求“" + item.getSourceFileName() + "”已经属于项目级项目“"
                                + state.getProjectGroupName() + "”（" + state.getProjectGroupId() + "），不能重复加入。");
                    }
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("检查需求的项目级归属失败。", ex);
        }
    }

    private BatchSplitState findProjectGroupState(String projectGroupId) {
        if (!Files.exists(batchBaseDir)) {
            throw new BusinessException("项目级项目不存在：" + projectGroupId);
        }
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(batchBaseDir, "BS*")) {
            for (Path directory : directories) {
                if (!Files.isRegularFile(directory.resolve("state.json"))) continue;
                try {
                    BatchSplitState state = loadState(directory.getFileName().toString());
                    if (projectGroupId.equals(state.getProjectGroupId())) {
                        return state;
                    }
                } catch (BusinessException ignored) {
                    // A corrupt unrelated historical batch must not hide a valid project group.
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("查找项目级项目失败：" + projectGroupId, ex);
        }
        throw new BusinessException("项目级项目不存在：" + projectGroupId);
    }

    private void assertProjectGroupDeletable(BatchSplitState state) {
        if (state == null || !StringUtils.hasText(state.getProjectGroupId())) {
            throw new BusinessException("项目级项目不存在。");
        }
        if ("GENERATING".equals(state.getProjectMaterialStatus())) {
            throw new BusinessException("项目“" + state.getProjectGroupName() + "”正在生成材料，暂不能删除。");
        }
        for (BatchSplitItemState item : state.getItems()) {
            if ("PARSE_QUEUED".equals(item.getStatus()) || "PREPARING".equals(item.getStatus())
                    || "QUEUED".equals(item.getStatus()) || "RUNNING".equals(item.getStatus())) {
                throw new BusinessException("项目“" + state.getProjectGroupName()
                        + "”仍有需求正在处理，完成或中断后才能删除。");
            }
        }
    }

    private void deleteBatchDirectory(String batchId) {
        Path root = batchBaseDir.toAbsolutePath().normalize();
        Path target = batchDir(batchId).toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new BusinessException("批次删除路径非法，已拒绝操作。");
        }
        deleteDirectoryTree(target, "删除项目级项目失败：" + batchId);
    }

    private void deleteDirectoryTree(Path target, String errorMessage) {
        if (!Files.exists(target)) return;
        try (Stream<Path> paths = Files.walk(target)) {
            List<Path> ordered = new ArrayList<Path>();
            paths.forEach(ordered::add);
            Collections.sort(ordered, new Comparator<Path>() {
                @Override
                public int compare(Path left, Path right) {
                    return right.compareTo(left);
                }
            });
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            throw new BusinessException(errorMessage, ex);
        }
    }

    public BatchSplitJobResponse get(String batchId) {
        return toResponse(loadState(batchId));
    }

    public BatchSplitSummaryResponse summary(String batchId) {
        BatchSplitState state = loadState(batchId);
        if (hasUnfinishedItems(state)) {
            throw new BusinessException("批量拆分尚未结束，请等待全部需求完成或失败后再查看摘要。");
        }

        BatchSplitSummaryResponse response = new BatchSplitSummaryResponse();
        response.setBatchId(state.getBatchId());
        response.setSplitLevel(normalizeSplitLevel(state.getSplitLevel()));
        response.setProjectGroupId(state.getProjectGroupId());
        response.setProjectGroupName(state.getProjectGroupName());
        response.setTotalCount(state.getItems().size());
        response.setProjectMaterialStatus(state.getProjectMaterialStatus());
        response.setProjectMaterialTotalCfp(roundCfp(state.getProjectMaterialTotalCfp()));

        List<BatchSplitSummaryItemResponse> items = new ArrayList<BatchSplitSummaryItemResponse>();
        int totalProcesses = 0;
        int totalRows = 0;
        double totalCfp = 0D;
        int completed = 0;
        int failed = 0;
        int statisticsAvailable = 0;
        for (BatchSplitItemState stateItem : state.getItems()) {
            BatchSplitSummaryItemResponse item = new BatchSplitSummaryItemResponse();
            item.setProjectId(stateItem.getProjectId());
            item.setProjectName(stateItem.getProjectName());
            item.setSourceFileName(stateItem.getSourceFileName());
            item.setStatus(stateItem.getStatus());
            if ("COMPLETED".equals(stateItem.getStatus())) {
                completed++;
                try {
                    List<FunctionSplitRow> rows = readFinalSplitRows(stateItem.getProjectId());
                    Set<String> processKeys = new LinkedHashSet<String>();
                    double itemCfp = 0D;
                    for (FunctionSplitRow row : rows) {
                        if (row == null) continue;
                        if (StringUtils.hasText(row.getFunctionProcess())) {
                            processKeys.add(FunctionProcessKey.of(row));
                        }
                        itemCfp += row.getCfp();
                    }
                    item.setFunctionProcessCount(processKeys.size());
                    item.setMovementRowCount(rows.size());
                    item.setTotalCfp(roundCfp(itemCfp));
                    item.setStatisticsAvailable(true);
                    statisticsAvailable++;
                    totalProcesses += processKeys.size();
                    totalRows += rows.size();
                    totalCfp += itemCfp;
                } catch (RuntimeException ex) {
                    item.setStatisticsAvailable(false);
                    item.setMessage("最终拆分结果读取失败：" + message(ex));
                }
            } else {
                if ("FAILED".equals(stateItem.getStatus())) failed++;
                item.setStatisticsAvailable(false);
                item.setMessage(StringUtils.hasText(stateItem.getErrorMessage())
                        ? stateItem.getErrorMessage() : "该需求未完成拆分，不计入 CFP 汇总。");
            }
            items.add(item);
        }
        response.setCompletedCount(completed);
        response.setFailedCount(failed);
        response.setStatisticsAvailableCount(statisticsAvailable);
        response.setFunctionProcessCount(totalProcesses);
        response.setMovementRowCount(totalRows);
        response.setTotalCfp(roundCfp(totalCfp));
        response.setItems(items);
        return response;
    }

    private boolean hasUnfinishedItems(BatchSplitState state) {
        for (BatchSplitItemState item : state.getItems()) {
            if (!"COMPLETED".equals(item.getStatus()) && !"FAILED".equals(item.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private List<FunctionSplitRow> readFinalSplitRows(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        if (metadata == null || !StringUtils.hasText(metadata.getFunctionSplitRowsPath())) {
            throw new BusinessException("未找到最终拆分表");
        }
        Path path = Paths.get(metadata.getFunctionSplitRowsPath());
        if (!Files.isRegularFile(path)) {
            throw new BusinessException("最终拆分表文件不存在");
        }
        try {
            List<FunctionSplitRow> rows = objectMapper.readValue(path.toFile(),
                    new TypeReference<List<FunctionSplitRow>>() { });
            return rows == null ? Collections.<FunctionSplitRow>emptyList() : rows;
        } catch (IOException ex) {
            throw new BusinessException("最终拆分表格式无效", ex);
        }
    }

    private double roundCfp(double value) {
        return Math.round(value * 100D) / 100D;
    }

    public BatchSplitJobResponse confirm(String batchId,
                                         String projectId,
                                         BatchSplitConfirmRequest request) {
        BatchSplitState current = loadState(batchId);
        BatchSplitItemState currentItem = findItem(current, projectId);
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
                item.setRequirementWarning(null);
                item.setErrorMessage(null);
            }
        });
        return get(batchId);
    }

    public BatchSplitJobResponse start(String batchId) {
        List<String> projectIds = new ArrayList<String>();
        synchronized (lockFor(batchId)) {
            BatchSplitState state = loadStateUnlocked(batchId);
            List<String> blockers = new ArrayList<String>();
            for (BatchSplitItemState item : state.getItems()) {
                if ("COMPLETED".equals(item.getStatus()) || "FAILED".equals(item.getStatus())
                        || "QUEUED".equals(item.getStatus()) || "RUNNING".equals(item.getStatus())) {
                    continue;
                }
                if (!"CONFIRMED".equals(item.getStatus())) {
                    blockers.add(item.getSourceFileName() + "（" + statusText(item.getStatus()) + "）");
                    continue;
                }
                if (item.isExistingRows()) {
                    projectIds.add(item.getProjectId());
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
                    continue;
                }
                projectIds.add(item.getProjectId());
            }
            if (!blockers.isEmpty()) {
                recomputeState(state);
                saveStateUnlocked(state);
                throw new BusinessException("还有文档未完成 AI 输入确认：" + join(blockers));
            }
            if (projectIds.isEmpty()) {
                throw new BusinessException("当前没有已确认且等待拆分的新增需求。");
            }
            state.setStatus("RUNNING");
            if (!StringUtils.hasText(state.getStartedAt())) {
                state.setStartedAt(now());
            }
            state.setFinishedAt(null);
            for (BatchSplitItemState item : state.getItems()) {
                if (projectIds.contains(item.getProjectId())) {
                    item.setStatus("QUEUED");
                    item.setStage("WAITING_AI_SPLIT");
                    item.setProgressPercent(0);
                    item.setStageMessage("等待进入 AI 智能拆分队列");
                    item.setErrorMessage(null);
                    item.setFinishedAt(null);
                }
            }
            saveStateUnlocked(state);
        }
        for (String projectId : projectIds) {
            submitExecution(batchId, projectId);
        }
        return get(batchId);
    }

    public BatchSplitJobResponse addItems(String batchId, BatchSplitItemsRequest request) {
        List<String> projectIds = normalizeProjectIds(request == null ? null : request.getProjectIds());
        if (projectIds.isEmpty()) {
            throw new BusinessException("请至少选择一个要添加的需求。");
        }
        List<String> preparationIds = new ArrayList<String>();
        List<String> reserved = new ArrayList<String>();
        try {
            synchronized (lockFor(batchId)) {
                BatchSplitState state = loadStateUnlocked(batchId);
                assertMaintainableProjectGroup(state);
                assertProjectGroupProjectsAvailable(projectIds, batchId);
                Set<String> existingIds = new HashSet<String>();
                for (BatchSplitItemState item : state.getItems()) {
                    existingIds.add(item.getProjectId());
                }
                for (String projectId : projectIds) {
                    if (existingIds.contains(projectId)) {
                        throw new BusinessException("需求已经在当前项目中：" + projectId);
                    }
                    ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
                    validateBatchProject(metadata);
                    validateProjectGroupMembership(state, metadata);
                    String existingBatch = assignedProjects.get(projectId);
                    if (existingBatch != null && !batchId.equals(existingBatch)) {
                        throw new BusinessException("文档正在另一个批次中处理：" + metadata.getSourceFileName());
                    }
                    boolean reuse = (request == null || request.isReuseCompletedResults())
                            && hasReusableSplitResult(metadata);
                    state.getItems().add(newItemState(metadata, reuse));
                    existingIds.add(projectId);
                    if (!reuse) {
                        String raced = assignedProjects.putIfAbsent(projectId, batchId);
                        if (raced != null && !batchId.equals(raced)) {
                            throw new BusinessException("文档正在另一个批次中处理：" + metadata.getSourceFileName());
                        }
                        reserved.add(projectId);
                        preparationIds.add(projectId);
                    }
                }
                invalidateProjectMaterials(state, "项目成员已变化，旧材料已转为历史版本；请人工确认后生成新版本。");
                associateProjects(state);
                recomputeState(state);
                saveStateUnlocked(state);
            }
        } catch (RuntimeException ex) {
            for (String projectId : reserved) {
                assignedProjects.remove(projectId, batchId);
            }
            throw ex;
        }
        for (String projectId : preparationIds) {
            submitPreparation(batchId, projectId);
        }
        return get(batchId);
    }

    public BatchSplitJobResponse rerunItem(String batchId, String projectId) {
        boolean executeImmediately;
        boolean newlyReserved = false;
        try {
            synchronized (lockFor(batchId)) {
                BatchSplitState state = loadStateUnlocked(batchId);
                if ("PROJECT".equals(normalizeSplitLevel(state.getSplitLevel()))
                        && "GENERATING".equals(state.getProjectMaterialStatus())) {
                    throw new BusinessException("项目级送审材料正在生成，请完成后再重新拆分需求。");
                }
                BatchSplitItemState item = findItem(state, projectId);
                if ("GENERATING".equals(state.getRequirementMaterialStatus())) {
                    throw new BusinessException("单需求送审材料总包正在生成，请完成后再重新拆分需求。");
                }
                if ("QUEUED".equals(item.getStatus()) || "RUNNING".equals(item.getStatus())
                        || "PARSE_QUEUED".equals(item.getStatus()) || "PREPARING".equals(item.getStatus())) {
                    throw new BusinessException("该需求正在处理，不能重复提交。");
                }
                String existingBatch = assignedProjects.putIfAbsent(projectId, batchId);
                if (existingBatch != null && !batchId.equals(existingBatch)) {
                    throw new BusinessException("该需求正在另一个批次中处理。");
                }
                newlyReserved = existingBatch == null;
                executeImmediately = item.isExistingRows() || snapshotExists(batchId, projectId);
                if (!item.isExistingRows() && !executeImmediately) {
                    String currentInput = readCurrentInput(projectId);
                    if (StringUtils.hasText(currentInput)) {
                        writeInputSnapshot(batchId, projectId, currentInput);
                        item.setConfirmedAt(now());
                        item.setInputHash(sha256(currentInput));
                        item.setInputLength(currentInput.length());
                        executeImmediately = true;
                    }
                }
                resetItemForRerun(item);
                if ("REQUIREMENT".equals(normalizeSplitLevel(state.getSplitLevel()))) {
                    state.setRequirementMaterialStatus("NOT_STARTED");
                    state.setRequirementMaterialMessage("需求已重新拆分，旧整合包已失效；完成后请重新生成。");
                    state.setRequirementMaterialArchivePath(null);
                    state.setRequirementMaterialSuccessCount(0);
                    state.setRequirementMaterialFailedCount(0);
                }
                if (executeImmediately) {
                    item.setStatus("QUEUED");
                    item.setStage("WAITING_AI_SPLIT");
                    item.setStageMessage("已请求重新拆分，等待进入 AI 智能拆分队列");
                } else {
                    item.setStatus("PARSE_QUEUED");
                    item.setStage("WAITING_PARSE");
                    item.setStageMessage("重新拆分前需要重新解析并确认 AI 输入");
                }
                if ("PROJECT".equals(normalizeSplitLevel(state.getSplitLevel()))) {
                    invalidateProjectMaterials(state, "需求“" + item.getSourceFileName()
                            + "”正在重新拆分，旧材料已转为历史版本。");
                }
                state.setFinishedAt(null);
                if (!StringUtils.hasText(state.getStartedAt())) {
                    state.setStartedAt(now());
                }
                recomputeState(state);
                saveStateUnlocked(state);
            }
        } catch (RuntimeException ex) {
            if (newlyReserved) {
                assignedProjects.remove(projectId, batchId);
            }
            throw ex;
        }
        if (executeImmediately) {
            submitExecution(batchId, projectId);
        } else {
            submitPreparation(batchId, projectId);
        }
        return get(batchId);
    }

    public BatchSplitJobResponse cancelItem(String batchId, String projectId) {
        final String key = executionKey(batchId, projectId);
        final boolean removedFromQueue;
        synchronized (lockFor(batchId)) {
            BatchSplitState state = loadStateUnlocked(batchId);
            BatchSplitItemState item = findItem(state, projectId);
            boolean queued = "QUEUED".equals(item.getStatus());
            boolean recognizing = "RUNNING".equals(item.getStatus())
                    && ("AI_EXTRACTING".equals(item.getStage()) || "CANCEL_REQUESTED".equals(item.getStage()));
            if (!queued && !recognizing) {
                throw new BusinessException("仅可取消排队中或正在 AI 识别的需求，其他需求不会受影响。");
            }
        }

        removedFromQueue = removePendingExecution(batchId, projectId);
        if (!removedFromQueue) {
            cancelledExecutions.add(key);
            smartSplitPipelineService.cancelDocument(projectId);
        }

        updateItem(batchId, projectId, new ItemMutation() {
            @Override
            public void apply(BatchSplitItemState item) {
                item.setAiAttemptDeadlineAt(null);
                if ("FAILED".equals(item.getStatus()) && "CANCELLED".equals(item.getStage())) {
                    return;
                }
                if (removedFromQueue) {
                    item.setStatus("FAILED");
                    item.setStage("CANCELLED");
                    item.setStageMessage("当前需求已取消，其他需求继续执行；可重新拆分当前需求");
                    item.setErrorMessage("AI 识别已由用户取消；重新拆分时仍使用 DeepSeek V4 Pro max。");
                    item.setFinishedAt(now());
                } else {
                    item.setStatus("RUNNING");
                    item.setStage("CANCEL_REQUESTED");
                    item.setStageMessage("正在取消当前需求的 AI 识别，其他需求继续执行");
                }
            }
        });
        if (removedFromQueue) {
            assignedProjects.remove(projectId, batchId);
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
                state.setProjectMaterialMessage("失败需求正在重试；全部需求拆分成功后请人工生成项目级送审材料。");
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
            if ("GENERATING".equals(state.getProjectMaterialStatus())) {
                throw new BusinessException("项目级送审材料正在生成，请等待完成。");
            }
            for (BatchSplitItemState item : state.getItems()) {
                if (!"COMPLETED".equals(item.getStatus())) {
                    throw new BusinessException("仍有需求未成功完成，暂不能生成项目级送审材料："
                            + item.getSourceFileName() + "（" + statusText(item.getStatus()) + "）");
                }
            }
            state.setProjectMaterialStatus("PENDING");
            state.setProjectMaterialMessage("已人工确认，等待生成项目级附件 1–5。");
            state.setProjectMaterialStage("QUEUED");
            state.setProjectMaterialProgressPercent(0);
            state.setProjectMaterialAiCompletedRequirements(0);
            state.setProjectMaterialAiTotalRequirements(0);
            state.setProjectMaterialAiCompletedFunctions(0);
            state.setProjectMaterialAiTotalFunctions(0);
            state.setProjectMaterialAiActiveRequirements(0);
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

    public BatchSplitJobResponse exportRequirementMaterials(final String batchId) {
        synchronized (lockFor(batchId)) {
            BatchSplitState state = loadStateUnlocked(batchId);
            if ("GENERATING".equals(state.getRequirementMaterialStatus())) {
                throw new BusinessException("单需求送审材料总包正在生成，请等待完成。");
            }
            int completed = 0;
            List<String> failedItems = new ArrayList<String>();
            for (BatchSplitItemState item : state.getItems()) {
                if ("COMPLETED".equals(item.getStatus())) completed++;
                if ("FAILED".equals(item.getStatus())) {
                    failedItems.add(item.getSourceFileName());
                }
                if ("QUEUED".equals(item.getStatus()) || "RUNNING".equals(item.getStatus())
                        || "PARSE_QUEUED".equals(item.getStatus()) || "PREPARING".equals(item.getStatus())) {
                    throw new BusinessException("批量拆分仍在执行，请完成后再生成送审材料整合包。");
                }
            }
            if (!failedItems.isEmpty()) {
                throw new BusinessException("存在处理失败的需求，已停止生成全部送审材料。请先修复并重试："
                        + join(failedItems));
            }
            if (completed == 0) {
                throw new BusinessException("当前批次没有已完成的需求，无法生成送审材料整合包。");
            }
            state.setRequirementMaterialStatus("GENERATING");
            state.setRequirementMaterialMessage("正在逐份生成附件 1–3 和单需求 ZIP，再汇总为总 ZIP，共 "
                    + completed + " 份。");
            state.setRequirementMaterialSuccessCount(0);
            state.setRequirementMaterialFailedCount(0);
            state.setRequirementMaterialArchivePath(null);
            saveStateUnlocked(state);
        }
        try {
            batchSplitExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    generateRequirementMaterials(batchId);
                }
            });
        } catch (RuntimeException ex) {
            failRequirementMaterials(batchId, "单需求送审材料总包生成队列已满，请稍后重试：" + message(ex));
        }
        return get(batchId);
    }

    private void generateRequirementMaterials(String batchId) {
        generateRequirementMaterials(batchId, false);
    }

    private void generateRequirementMaterials(String batchId, boolean reuseExistingExports) {
        List<String> projectIds = new ArrayList<String>();
        synchronized (lockFor(batchId)) {
            BatchSplitState state = loadStateUnlocked(batchId);
            if (!"GENERATING".equals(state.getRequirementMaterialStatus())) return;
            for (BatchSplitItemState item : state.getItems()) {
                if ("COMPLETED".equals(item.getStatus())) projectIds.add(item.getProjectId());
            }
        }
        try {
            ProjectMaterialExportService.RequirementArchiveProgressListener listener =
                            new ProjectMaterialExportService.RequirementArchiveProgressListener() {
                                @Override
                                public void onProgress(int successCount, int failedCount, int totalCount) {
                                    synchronized (lockFor(batchId)) {
                                        BatchSplitState state = loadStateUnlocked(batchId);
                                        if (!"GENERATING".equals(state.getRequirementMaterialStatus())) {
                                            return;
                                        }
                                        state.setRequirementMaterialSuccessCount(successCount);
                                        state.setRequirementMaterialFailedCount(failedCount);
                                        state.setRequirementMaterialMessage("正在逐份生成单需求送审材料：已处理 "
                                                + (successCount + failedCount) + "/" + totalCount
                                                + "，成功 " + successCount + "，失败 " + failedCount + "。");
                                        recomputeState(state);
                                        saveStateUnlocked(state);
                                    }
                                }
                            };
            ProjectMaterialExportService.RequirementBatchArchive archive = reuseExistingExports
                    ? projectMaterialExportService.exportExistingRequirementArchives(batchId, projectIds, listener)
                    : projectMaterialExportService.exportRequirementArchives(batchId, projectIds, listener);
            synchronized (lockFor(batchId)) {
                BatchSplitState state = loadStateUnlocked(batchId);
                if (!"GENERATING".equals(state.getRequirementMaterialStatus())) {
                    return;
                }
                state.setRequirementMaterialArchivePath(
                        archive.getArchivePath().toAbsolutePath().normalize().toString());
                state.setRequirementMaterialSuccessCount(archive.getSuccessCount());
                state.setRequirementMaterialFailedCount(archive.getFailedProjects().size());
                state.setRequirementMaterialStatus(archive.getFailedProjects().isEmpty()
                        ? "COMPLETED" : "PARTIAL_COMPLETED");
                state.setRequirementMaterialMessage(archive.getFailedProjects().isEmpty()
                        ? "产物 2 已生成：包含全部 " + archive.getSuccessCount() + " 份单需求送审材料。"
                        : "已打包成功 " + archive.getSuccessCount() + " 份，失败 "
                        + archive.getFailedProjects().size() + " 份："
                        + String.join("；", archive.getFailedProjects()));
                recomputeState(state);
                saveStateUnlocked(state);
            }
        } catch (RuntimeException ex) {
            failRequirementMaterials(batchId, message(ex));
        }
    }

    private void failRequirementMaterials(String batchId, String errorMessage) {
        synchronized (lockFor(batchId)) {
            BatchSplitState state = loadStateUnlocked(batchId);
            state.setRequirementMaterialStatus("FAILED");
            state.setRequirementMaterialMessage(errorMessage);
            recomputeState(state);
            saveStateUnlocked(state);
        }
    }

    public Path requirementMaterialArchiveFile(String batchId) {
        BatchSplitState state = loadState(batchId);
        if (!StringUtils.hasText(state.getRequirementMaterialArchivePath())) {
            throw new BusinessException("单需求送审材料总包尚未生成。");
        }
        Path file = Paths.get(state.getRequirementMaterialArchivePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new BusinessException("单需求送审材料总包文件不存在：" + file.getFileName());
        }
        return file;
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
                final boolean adjustReuseDegree = loadState(batchId).isReuseDegreeAdjustmentEnabled();
                updateItem(batchId, projectId, new ItemMutation() {
                    @Override
                    public void apply(BatchSplitItemState item) {
                        item.setStatus("CONFIRMED");
                        item.setStage("INPUT_CONFIRMED");
                        item.setStageMessage(adjustReuseDegree
                                ? "已导入拆分表，将执行复用度 AI 调整和规则初审"
                                : "已导入拆分表，将保留人工复用度并执行规则初审");
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

    private boolean removePendingExecution(String batchId, String projectId) {
        synchronized (executionScheduleLock) {
            Deque<String> projects = pendingExecutions.get(batchId);
            if (projects == null || !projects.remove(projectId)) {
                return false;
            }
            scheduledExecutions.remove(executionKey(batchId, projectId));
            if (projects.isEmpty()) {
                pendingExecutions.remove(batchId);
                while (pendingBatchOrder.remove(batchId)) {
                    // Remove every stale round-robin occurrence for this batch.
                }
            }
            return true;
        }
    }

    private void executeItem(String batchId, String projectId) {
        final String currentExecutionKey = executionKey(batchId, projectId);
        if (cancelledExecutions.remove(currentExecutionKey)) {
            failCancelledItem(batchId, projectId);
            return;
        }
        updateItem(batchId, projectId, new ItemMutation() {
            @Override
            public void apply(BatchSplitItemState item) {
                item.setStatus("RUNNING");
                item.setAttempt(item.getAttempt() + 1);
                item.setStartedAt(now());
                item.setFinishedAt(null);
                item.setAiAttempt(0);
                item.setAiMaxAttempts(0);
                item.setAiAttemptStartedAt(null);
                item.setAiAttemptDeadlineAt(null);
                item.setAiPartitions(new ArrayList<AiPartitionProgressItem>());
            }
        });
        try {
            BatchSplitState currentState = loadState(batchId);
            BatchSplitItemState current = findItem(currentState, projectId);
            final boolean adjustReuseDegree = currentState.isReuseDegreeAdjustmentEnabled();
            SmartSplitPipelineService.StageListener listener = new SmartSplitPipelineService.StageListener() {
                @Override
                public void onStage(final String stage, final int progressPercent, final String stageMessage) {
                    updateItem(batchId, projectId, new ItemMutation() {
                        @Override
                        public void apply(BatchSplitItemState item) {
                            if (cancelledExecutions.contains(currentExecutionKey)
                                    || "CANCEL_REQUESTED".equals(item.getStage())) {
                                return;
                            }
                            item.setStatus("RUNNING");
                            item.setStage(stage);
                            item.setProgressPercent(progressPercent);
                            item.setStageMessage(stageMessage);
                        }
                    });
                }

                @Override
                public void onAiAttempt(final int attempt, final int maxAttempts, final long timeoutSeconds) {
                    updateItem(batchId, projectId, new ItemMutation() {
                        @Override
                        public void apply(BatchSplitItemState item) {
                            if (cancelledExecutions.contains(currentExecutionKey)
                                    || "CANCEL_REQUESTED".equals(item.getStage())) {
                                return;
                            }
                            // Partition progress is authoritative for concurrent extraction.
                            // Do not let one worker's transport callback replace the aggregate
                            // status with the old serial "current part" presentation.
                            if (item.getAiPartitions() != null && !item.getAiPartitions().isEmpty()) {
                                return;
                            }
                            LocalDateTime started = LocalDateTime.now();
                            item.setStatus("RUNNING");
                            item.setStage("AI_EXTRACTING");
                            item.setProgressPercent(Math.max(8, item.getProgressPercent()));
                            item.setAiAttempt(attempt);
                            item.setAiMaxAttempts(maxAttempts);
                            item.setAiAttemptStartedAt(started.format(DATE_TIME_FORMATTER));
                            item.setAiAttemptDeadlineAt(null);
                            item.setStageMessage(maxAttempts > 1
                                    ? "AI 识别第 " + attempt + "/" + maxAttempts
                                        + " 段，DeepSeek V4 Pro max；当前分段只发送一次，不自动重试"
                                    : "AI 识别单次请求，DeepSeek V4 Pro max；持续等待模型返回，不自动重试");
                        }
                    });
                }

                @Override
                public void onAiPartitionProgress(final List<AiPartitionProgressItem> partitions) {
                    updateItem(batchId, projectId, new ItemMutation() {
                        @Override
                        public void apply(BatchSplitItemState item) {
                            if (cancelledExecutions.contains(currentExecutionKey)
                                    || "CANCEL_REQUESTED".equals(item.getStage())) return;
                            List<AiPartitionProgressItem> snapshot = copyPartitionProgress(partitions);
                            int completed = 0;
                            int cached = 0;
                            int running = 0;
                            int failed = 0;
                            for (AiPartitionProgressItem part : snapshot) {
                                if ("COMPLETED".equals(part.getStatus())) completed++;
                                else if ("CACHED".equals(part.getStatus())) cached++;
                                else if ("RUNNING".equals(part.getStatus())) running++;
                                else if ("FAILED".equals(part.getStatus())) failed++;
                            }
                            int done = completed + cached;
                            int queued = Math.max(0, snapshot.size() - done - running - failed);
                            String phase = snapshot.isEmpty() ? "EXTRACTION" : snapshot.get(0).getPhase();
                            boolean reusePhase = "REUSE_ADJUSTMENT".equals(phase);
                            item.setStatus("RUNNING");
                            item.setStage(reusePhase ? "REUSE_ADJUSTING" : "AI_EXTRACTING");
                            item.setProgressPercent(Math.max(reusePhase ? 62 : 8, item.getProgressPercent()));
                            item.setAiAttempt(done);
                            item.setAiMaxAttempts(snapshot.size());
                            item.setAiPartitions(snapshot);
                            item.setStageMessage(done == snapshot.size() && failed == 0
                                    ? (reusePhase ? "复用度 AI" : "AI 初拆")
                                        + "全部分段已返回，正在本地合并结果"
                                    : (reusePhase ? "复用度 AI" : "AI 初拆")
                                        + "并发分段：完成 " + done + "/" + snapshot.size()
                                        + "（缓存 " + cached + "），运行 " + running
                                        + "，排队 " + queued + "，失败 " + failed);
                        }
                    });
                }
            };
            final SmartSplitPipelineService.PipelineResult pipeline;
            if (current.isExistingRows()) {
                pipeline = smartSplitPipelineService.runExistingRows(projectId, adjustReuseDegree, listener);
            } else {
                String frozenInput = readInputSnapshot(batchId, projectId);
                requirementInputService.saveManualInput(projectId, frozenInput);
                pipeline = smartSplitPipelineService.runDocument(projectId, "", listener);
            }
            if (cancelledExecutions.remove(currentExecutionKey)) {
                throw new AiRequestCancelledException("AI 识别已由用户取消");
            }
            final RuleValidationResponse validation = pipeline.getValidation();
            updateItem(batchId, projectId, new ItemMutation() {
                @Override
                public void apply(BatchSplitItemState item) {
                    item.setStatus("COMPLETED");
                    item.setStage("COMPLETED");
                    item.setProgressPercent(100);
                    item.setStageMessage(current.isExistingRows() && !adjustReuseDegree
                            ? "已保留 Excel 人工拆分结果并完成规则初审"
                            : (pipeline.isPartial()
                            ? "智能拆分部分完成，已进入人工审核"
                            : "智能拆分、复用度调整和规则初审已完成"));
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
                    item.setAiAttemptDeadlineAt(null);
                }
            });
            assignedProjects.remove(projectId, batchId);
        } catch (Throwable ex) {
            boolean cancelled = ex instanceof AiRequestCancelledException;
            if (cancelledExecutions.remove(currentExecutionKey)) {
                cancelled = true;
            }
            if (cancelled) {
                failCancelledItem(batchId, projectId);
            } else {
                failItem(batchId, projectId, currentStage(batchId, projectId), message(ex));
            }
            if (ex instanceof VirtualMachineError) {
                throw (VirtualMachineError) ex;
            }
            if (ex instanceof ThreadDeath) {
                throw (ThreadDeath) ex;
            }
        }
    }

    private void failCancelledItem(String batchId, String projectId) {
        updateItem(batchId, projectId, new ItemMutation() {
            @Override
            public void apply(BatchSplitItemState item) {
                item.setStatus("FAILED");
                item.setStage("CANCELLED");
                item.setStageMessage("当前需求已取消，其他需求继续执行；可重新拆分当前需求");
                item.setErrorMessage("AI 识别已由用户取消；重新拆分时仍使用 DeepSeek V4 Pro max。");
                item.setFinishedAt(now());
                item.setAiAttemptDeadlineAt(null);
            }
        });
        assignedProjects.remove(projectId, batchId);
    }

    private void failItem(String batchId, String projectId, final String stage, final String errorMessage) {
        updateItem(batchId, projectId, new ItemMutation() {
            @Override
            public void apply(BatchSplitItemState item) {
                item.setStatus("FAILED");
                item.setStage(stage);
                item.setErrorMessage(compactErrorMessage(errorMessage));
                item.setFinishedAt(now());
                item.setAiAttemptDeadlineAt(null);
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
        String materialId;
        int nextVersion;
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
            nextVersion = Math.max(0, state.getProjectMaterialVersion()) + 1;
            materialId = StringUtils.hasText(state.getProjectGroupId())
                    ? state.getProjectGroupId() + "-V" + nextVersion
                    : batchId + "-V" + nextVersion;
            state.setProjectMaterialStatus("GENERATING");
            state.setProjectMaterialStage("AI_FUNCTION_DESCRIPTIONS");
            state.setProjectMaterialProgressPercent(1);
            state.setProjectMaterialAiCompletedRequirements(0);
            state.setProjectMaterialAiTotalRequirements(projectIds.size());
            state.setProjectMaterialAiCompletedFunctions(0);
            state.setProjectMaterialAiTotalFunctions(0);
            state.setProjectMaterialAiActiveRequirements(0);
            state.setProjectMaterialMessage("正在准备按单个需求并发调用大模型生成功能描述，随后生成项目级附件 1–5。");
            state.setProjectMaterialCompletedAt(null);
            recomputeState(state);
            saveStateUnlocked(state);
        }

        try {
            ProjectMaterialExportService.ProjectMaterialBundle bundle =
                    projectMaterialExportService.export(materialId, projectIds,
                            new ProjectMaterialExportService.ProjectMaterialProgressListener() {
                                @Override
                                public void onProgress(String stage,
                                                       int progressPercent,
                                                       String progressMessage,
                                                       int completedRequirements,
                                                       int totalRequirements,
                                                       int completedFunctions,
                                                       int totalFunctions,
                                                       int activeRequirements) {
                                    synchronized (lockFor(batchId)) {
                                        BatchSplitState progressState = loadStateUnlocked(batchId);
                                        if (!"GENERATING".equals(progressState.getProjectMaterialStatus())) {
                                            return;
                                        }
                                        progressState.setProjectMaterialStage(stage);
                                        progressState.setProjectMaterialProgressPercent(Math.max(
                                                progressState.getProjectMaterialProgressPercent(), progressPercent));
                                        progressState.setProjectMaterialMessage(progressMessage);
                                        progressState.setProjectMaterialAiCompletedRequirements(Math.max(
                                                progressState.getProjectMaterialAiCompletedRequirements(), completedRequirements));
                                        if (totalRequirements > 0) {
                                            progressState.setProjectMaterialAiTotalRequirements(totalRequirements);
                                        }
                                        progressState.setProjectMaterialAiCompletedFunctions(Math.max(
                                                progressState.getProjectMaterialAiCompletedFunctions(), completedFunctions));
                                        if (totalFunctions > 0) {
                                            progressState.setProjectMaterialAiTotalFunctions(totalFunctions);
                                        }
                                        progressState.setProjectMaterialAiActiveRequirements(activeRequirements);
                                        recomputeState(progressState);
                                        saveStateUnlocked(progressState);
                                    }
                                }
                            });
            synchronized (lockFor(batchId)) {
                BatchSplitState state = loadStateUnlocked(batchId);
                state.getProjectMaterialFiles().clear();
                for (java.util.Map.Entry<String, Path> entry : bundle.getFiles().entrySet()) {
                    state.getProjectMaterialFiles().put(entry.getKey(),
                            entry.getValue().toAbsolutePath().normalize().toString());
                }
                state.setProjectMaterialStatus("COMPLETED");
                state.setProjectMaterialStage("COMPLETED");
                state.setProjectMaterialProgressPercent(100);
                state.setProjectMaterialAiActiveRequirements(0);
                state.setProjectMaterialVersion(nextVersion);
                state.setProjectMaterialMessage("产物 1 已生成：项目级送审材料 V" + nextVersion
                        + "，共包含附件 1–5。");
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
            state.setProjectMaterialStage("FAILED");
            state.setProjectMaterialAiActiveRequirements(0);
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
                    + " 个需求拆分失败，项目级送审材料已暂停；请先重试失败项，全部成功后再人工生成。");
            state.setProjectMaterialCompletedAt(null);
        }
        if (allCompleted) {
            if ("PROJECT".equals(state.getSplitLevel())) {
                if ("COMPLETED".equals(state.getProjectMaterialStatus())
                        && "GENERATING".equals(state.getRequirementMaterialStatus())) {
                    state.setStatus("GENERATING_MATERIALS");
                    state.setFinishedAt(null);
                } else if ("COMPLETED".equals(state.getProjectMaterialStatus())) {
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
                    state.setFinishedAt(now());
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

    private String generateProjectGroupId() {
        return "PJ" + LocalDateTime.now().format(ID_TIME_FORMATTER)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private String normalizeProjectGroupId(String projectGroupId) {
        String value = trim(projectGroupId).toUpperCase(Locale.ROOT);
        if (!value.matches("PJ[0-9A-Z]+")) {
            throw new BusinessException("无效的项目级项目编号。");
        }
        return value;
    }

    private String resolveProjectGroupName(BatchSplitState state, String requestedName) {
        if (StringUtils.hasText(requestedName)) {
            return trim(requestedName);
        }
        if (state != null) {
            if (StringUtils.hasText(state.getProjectGroupName())) {
                return trim(state.getProjectGroupName());
            }
            for (BatchSplitItemState item : state.getItems()) {
                if (StringUtils.hasText(item.getProjectName())) {
                    return trim(item.getProjectName());
                }
            }
        }
        return "未命名项目";
    }

    private String resolveProjectGroupName(BatchSplitState state,
                                           String requestedName,
                                           List<String> projectIds) {
        if (StringUtils.hasText(requestedName)) {
            return trim(requestedName);
        }
        if (state != null && StringUtils.hasText(state.getProjectGroupName())) {
            return trim(state.getProjectGroupName());
        }
        if (projectIds != null) {
            for (String projectId : projectIds) {
                ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
                if (metadata != null && StringUtils.hasText(metadata.getName())) {
                    return trim(metadata.getName());
                }
            }
        }
        return resolveProjectGroupName(state, null);
    }

    private void validateProjectGroupMembership(BatchSplitState state, ProjectMetadata metadata) {
        if (state == null || !"PROJECT".equals(normalizeSplitLevel(state.getSplitLevel()))) {
            return;
        }
        if (metadata == null || !StringUtils.hasText(metadata.getId())) {
            throw new BusinessException("不能向项目级项目加入不存在的需求。");
        }
    }

    private boolean hasReusableSplitResult(ProjectMetadata metadata) {
        if (metadata == null || !StringUtils.hasText(metadata.getFunctionSplitRowsPath())) {
            return false;
        }
        return Files.isRegularFile(Paths.get(metadata.getFunctionSplitRowsPath()));
    }

    private boolean projectMetadataExists(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return false;
        }
        try {
            ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
            return metadata != null && StringUtils.hasText(metadata.getId());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private BatchSplitItemState newItemState(ProjectMetadata metadata, boolean reuseCompletedResult) {
        BatchSplitItemState item = new BatchSplitItemState();
        item.setProjectId(metadata.getId());
        item.setProjectName(metadata.getName());
        item.setSourceFileName(metadata.getSourceFileName());
        item.setExistingRows(isExistingRowsProject(metadata));
        if (reuseCompletedResult) {
            item.setStatus("COMPLETED");
            item.setStage("REUSED_COMPLETED");
            item.setProgressPercent(100);
            item.setStageMessage("已复用需求级最终拆分结果");
            item.setLastCompletedStage("RULE_VALIDATION");
            item.setFinishedAt(now());
            item.setIssueCount(valueOrZero(metadata.getIssueCount()));
            item.setErrorCount(valueOrZero(metadata.getErrorCount()));
            item.setRiskCount(valueOrZero(metadata.getRiskCount()));
        } else {
            item.setStatus("PARSE_QUEUED");
            item.setStage("WAITING_PARSE");
            item.setStageMessage("等待进入文档解析队列");
        }
        return item;
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private void assertMaintainableProjectGroup(BatchSplitState state) {
        if (state == null || !"PROJECT".equals(normalizeSplitLevel(state.getSplitLevel()))
                || !StringUtils.hasText(state.getProjectGroupId())) {
            throw new BusinessException("只有项目级项目支持增量维护需求。");
        }
        if ("GENERATING".equals(state.getProjectMaterialStatus())) {
            throw new BusinessException("项目级送审材料正在生成，请完成后再维护项目成员。");
        }
        if ("GENERATING".equals(state.getRequirementMaterialStatus())) {
            throw new BusinessException("项目全量单需求送审材料总包正在生成，请完成后再维护项目成员。");
        }
    }

    private void invalidateProjectMaterials(BatchSplitState state, String message) {
        state.getProjectMaterialFiles().clear();
        state.setProjectMaterialStatus("PENDING");
        state.setProjectMaterialMessage(message);
        state.setProjectMaterialCompletedAt(null);
        state.setProjectMaterialTotalCfp(0D);
        state.setProjectMaterialRequirementCount(0);
        state.setRequirementMaterialStatus("NOT_STARTED");
        state.setRequirementMaterialMessage("项目成员或拆分结果已变化，旧的全量单需求送审材料总包已失效；请人工确认后重新生成。");
        state.setRequirementMaterialArchivePath(null);
        state.setRequirementMaterialSuccessCount(0);
        state.setRequirementMaterialFailedCount(0);
        state.setFinishedAt(null);
    }

    private void resetItemForRerun(BatchSplitItemState item) {
        item.setProgressPercent(0);
        item.setLastCompletedStage(null);
        item.setErrorMessage(null);
        item.setStartedAt(null);
        item.setFinishedAt(null);
        item.setAiAttempt(0);
        item.setAiMaxAttempts(0);
        item.setAiAttemptStartedAt(null);
        item.setAiAttemptDeadlineAt(null);
        item.setAiPartitions(new ArrayList<AiPartitionProgressItem>());
        item.setIssueCount(0);
        item.setErrorCount(0);
        item.setRiskCount(0);
        item.setDeepSuggestionCount(0);
        item.setAppliedSuggestionCount(0);
        item.setSkippedSuggestionCount(0);
        item.setPartial(false);
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

    private List<AiPartitionProgressItem> copyPartitionProgress(List<AiPartitionProgressItem> source) {
        List<AiPartitionProgressItem> result = new ArrayList<AiPartitionProgressItem>();
        for (AiPartitionProgressItem item : source == null
                ? Collections.<AiPartitionProgressItem>emptyList() : source) {
            if (item != null) result.add(new AiPartitionProgressItem(item));
        }
        return result;
    }

    private BatchSplitJobResponse toResponse(BatchSplitState state) {
        BatchSplitJobResponse response = new BatchSplitJobResponse();
        response.setBatchId(state.getBatchId());
        response.setSplitLevel(normalizeSplitLevel(state.getSplitLevel()));
        response.setProjectGroupId(state.getProjectGroupId());
        response.setProjectGroupName(state.getProjectGroupName());
        response.setReuseDegreeAdjustmentEnabled(state.isReuseDegreeAdjustmentEnabled());
        response.setStatus(state.getStatus());
        response.setCreatedAt(state.getCreatedAt());
        response.setUpdatedAt(state.getUpdatedAt());
        response.setStartedAt(state.getStartedAt());
        response.setFinishedAt(state.getFinishedAt());
        response.setTotal(state.getItems().size());
        response.setProjectMaterialStatus(StringUtils.hasText(state.getProjectMaterialStatus())
                ? state.getProjectMaterialStatus() : "NOT_REQUIRED");
        response.setProjectMaterialMessage(state.getProjectMaterialMessage());
        response.setProjectMaterialStage(state.getProjectMaterialStage());
        response.setProjectMaterialProgressPercent(state.getProjectMaterialProgressPercent());
        response.setProjectMaterialAiCompletedRequirements(state.getProjectMaterialAiCompletedRequirements());
        response.setProjectMaterialAiTotalRequirements(state.getProjectMaterialAiTotalRequirements());
        response.setProjectMaterialAiCompletedFunctions(state.getProjectMaterialAiCompletedFunctions());
        response.setProjectMaterialAiTotalFunctions(state.getProjectMaterialAiTotalFunctions());
        response.setProjectMaterialAiActiveRequirements(state.getProjectMaterialAiActiveRequirements());
        response.setProjectMaterialCompletedAt(state.getProjectMaterialCompletedAt());
        response.setProjectMaterialTotalCfp(state.getProjectMaterialTotalCfp());
        response.setProjectMaterialRequirementCount(state.getProjectMaterialRequirementCount());
        response.setProjectMaterialVersion(state.getProjectMaterialVersion());
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
        response.setRequirementMaterialStatus(StringUtils.hasText(state.getRequirementMaterialStatus())
                ? state.getRequirementMaterialStatus() : "NOT_STARTED");
        response.setRequirementMaterialMessage(state.getRequirementMaterialMessage());
        response.setRequirementMaterialSuccessCount(state.getRequirementMaterialSuccessCount());
        response.setRequirementMaterialFailedCount(state.getRequirementMaterialFailedCount());
        if (StringUtils.hasText(state.getRequirementMaterialArchivePath())) {
            Path archivePath = Paths.get(state.getRequirementMaterialArchivePath());
            ProjectMaterialFileResponse archive = new ProjectMaterialFileResponse();
            archive.setType("requirementArchive");
            archive.setName(archivePath.getFileName().toString());
            archive.setPath(state.getRequirementMaterialArchivePath());
            archive.setDownloadUrl("/api/projects/batch-split/" + state.getBatchId()
                    + "/requirement-materials/download");
            response.setRequirementMaterialArchive(archive);
        }
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
            target.setAiAttempt(item.getAiAttempt());
            target.setAiMaxAttempts(item.getAiMaxAttempts());
            target.setAiAttemptStartedAt(item.getAiAttemptStartedAt());
            target.setAiAttemptDeadlineAt(item.getAiAttemptDeadlineAt());
            target.setAiPartitions(copyPartitionProgress(item.getAiPartitions()));
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
        if (text.length() <= 4000) {
            return text;
        }
        return text.substring(0, 3000) + " ...[超长技术详情已截短]... "
                + text.substring(text.length() - 500);
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
        private String projectGroupId;
        private String projectGroupName;
        private boolean reuseDegreeAdjustmentEnabled = true;
        private String status;
        private String createdAt;
        private String updatedAt;
        private String startedAt;
        private String finishedAt;
        private String projectMaterialStatus = "NOT_REQUIRED";
        private String projectMaterialMessage;
        private String projectMaterialStage;
        private int projectMaterialProgressPercent;
        private int projectMaterialAiCompletedRequirements;
        private int projectMaterialAiTotalRequirements;
        private int projectMaterialAiCompletedFunctions;
        private int projectMaterialAiTotalFunctions;
        private int projectMaterialAiActiveRequirements;
        private String projectMaterialCompletedAt;
        private double projectMaterialTotalCfp;
        private int projectMaterialRequirementCount;
        private int projectMaterialVersion;
        private Map<String, String> projectMaterialFiles = new LinkedHashMap<String, String>();
        private String requirementMaterialStatus = "NOT_STARTED";
        private String requirementMaterialMessage;
        private String requirementMaterialArchivePath;
        private int requirementMaterialSuccessCount;
        private int requirementMaterialFailedCount;
        private List<BatchSplitItemState> items = new ArrayList<BatchSplitItemState>();

        public String getBatchId() { return batchId; }
        public void setBatchId(String batchId) { this.batchId = batchId; }
        public String getSplitLevel() { return splitLevel; }
        public void setSplitLevel(String splitLevel) { this.splitLevel = splitLevel; }
        public String getProjectGroupId() { return projectGroupId; }
        public void setProjectGroupId(String projectGroupId) { this.projectGroupId = projectGroupId; }
        public String getProjectGroupName() { return projectGroupName; }
        public void setProjectGroupName(String projectGroupName) { this.projectGroupName = projectGroupName; }
        public boolean isReuseDegreeAdjustmentEnabled() { return reuseDegreeAdjustmentEnabled; }
        public void setReuseDegreeAdjustmentEnabled(boolean reuseDegreeAdjustmentEnabled) { this.reuseDegreeAdjustmentEnabled = reuseDegreeAdjustmentEnabled; }
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
        public String getProjectMaterialStage() { return projectMaterialStage; }
        public void setProjectMaterialStage(String projectMaterialStage) { this.projectMaterialStage = projectMaterialStage; }
        public int getProjectMaterialProgressPercent() { return projectMaterialProgressPercent; }
        public void setProjectMaterialProgressPercent(int projectMaterialProgressPercent) { this.projectMaterialProgressPercent = projectMaterialProgressPercent; }
        public int getProjectMaterialAiCompletedRequirements() { return projectMaterialAiCompletedRequirements; }
        public void setProjectMaterialAiCompletedRequirements(int projectMaterialAiCompletedRequirements) { this.projectMaterialAiCompletedRequirements = projectMaterialAiCompletedRequirements; }
        public int getProjectMaterialAiTotalRequirements() { return projectMaterialAiTotalRequirements; }
        public void setProjectMaterialAiTotalRequirements(int projectMaterialAiTotalRequirements) { this.projectMaterialAiTotalRequirements = projectMaterialAiTotalRequirements; }
        public int getProjectMaterialAiCompletedFunctions() { return projectMaterialAiCompletedFunctions; }
        public void setProjectMaterialAiCompletedFunctions(int projectMaterialAiCompletedFunctions) { this.projectMaterialAiCompletedFunctions = projectMaterialAiCompletedFunctions; }
        public int getProjectMaterialAiTotalFunctions() { return projectMaterialAiTotalFunctions; }
        public void setProjectMaterialAiTotalFunctions(int projectMaterialAiTotalFunctions) { this.projectMaterialAiTotalFunctions = projectMaterialAiTotalFunctions; }
        public int getProjectMaterialAiActiveRequirements() { return projectMaterialAiActiveRequirements; }
        public void setProjectMaterialAiActiveRequirements(int projectMaterialAiActiveRequirements) { this.projectMaterialAiActiveRequirements = projectMaterialAiActiveRequirements; }
        public String getProjectMaterialCompletedAt() { return projectMaterialCompletedAt; }
        public void setProjectMaterialCompletedAt(String projectMaterialCompletedAt) { this.projectMaterialCompletedAt = projectMaterialCompletedAt; }
        public double getProjectMaterialTotalCfp() { return projectMaterialTotalCfp; }
        public void setProjectMaterialTotalCfp(double projectMaterialTotalCfp) { this.projectMaterialTotalCfp = projectMaterialTotalCfp; }
        public int getProjectMaterialRequirementCount() { return projectMaterialRequirementCount; }
        public void setProjectMaterialRequirementCount(int projectMaterialRequirementCount) { this.projectMaterialRequirementCount = projectMaterialRequirementCount; }
        public int getProjectMaterialVersion() { return projectMaterialVersion; }
        public void setProjectMaterialVersion(int projectMaterialVersion) { this.projectMaterialVersion = projectMaterialVersion; }
        public Map<String, String> getProjectMaterialFiles() {
            if (projectMaterialFiles == null) projectMaterialFiles = new LinkedHashMap<String, String>();
            return projectMaterialFiles;
        }
        public void setProjectMaterialFiles(Map<String, String> projectMaterialFiles) {
            this.projectMaterialFiles = projectMaterialFiles == null
                    ? new LinkedHashMap<String, String>() : projectMaterialFiles;
        }
        public String getRequirementMaterialStatus() { return requirementMaterialStatus; }
        public void setRequirementMaterialStatus(String requirementMaterialStatus) { this.requirementMaterialStatus = requirementMaterialStatus; }
        public String getRequirementMaterialMessage() { return requirementMaterialMessage; }
        public void setRequirementMaterialMessage(String requirementMaterialMessage) { this.requirementMaterialMessage = requirementMaterialMessage; }
        public String getRequirementMaterialArchivePath() { return requirementMaterialArchivePath; }
        public void setRequirementMaterialArchivePath(String requirementMaterialArchivePath) { this.requirementMaterialArchivePath = requirementMaterialArchivePath; }
        public int getRequirementMaterialSuccessCount() { return requirementMaterialSuccessCount; }
        public void setRequirementMaterialSuccessCount(int requirementMaterialSuccessCount) { this.requirementMaterialSuccessCount = requirementMaterialSuccessCount; }
        public int getRequirementMaterialFailedCount() { return requirementMaterialFailedCount; }
        public void setRequirementMaterialFailedCount(int requirementMaterialFailedCount) { this.requirementMaterialFailedCount = requirementMaterialFailedCount; }
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
        private int aiAttempt;
        private int aiMaxAttempts;
        private String aiAttemptStartedAt;
        private String aiAttemptDeadlineAt;
        private List<AiPartitionProgressItem> aiPartitions = new ArrayList<AiPartitionProgressItem>();
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
        public int getAiAttempt() { return aiAttempt; }
        public void setAiAttempt(int aiAttempt) { this.aiAttempt = aiAttempt; }
        public int getAiMaxAttempts() { return aiMaxAttempts; }
        public void setAiMaxAttempts(int aiMaxAttempts) { this.aiMaxAttempts = aiMaxAttempts; }
        public String getAiAttemptStartedAt() { return aiAttemptStartedAt; }
        public void setAiAttemptStartedAt(String aiAttemptStartedAt) { this.aiAttemptStartedAt = aiAttemptStartedAt; }
        public String getAiAttemptDeadlineAt() { return aiAttemptDeadlineAt; }
        public void setAiAttemptDeadlineAt(String aiAttemptDeadlineAt) { this.aiAttemptDeadlineAt = aiAttemptDeadlineAt; }
        public List<AiPartitionProgressItem> getAiPartitions() { return aiPartitions; }
        public void setAiPartitions(List<AiPartitionProgressItem> aiPartitions) {
            this.aiPartitions = aiPartitions == null
                    ? new ArrayList<AiPartitionProgressItem>() : aiPartitions;
        }
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
