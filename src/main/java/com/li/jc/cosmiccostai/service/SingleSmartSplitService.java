package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.dto.SmartSplitJobResponse;
import com.li.jc.cosmiccostai.dto.SmartSplitStageTiming;
import com.li.jc.cosmiccostai.dto.SmartSplitStartRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class SingleSmartSplitService {

    private static final DateTimeFormatter JOB_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileStorageService fileStorageService;
    private final SmartSplitPipelineService pipelineService;
    private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, String> activeJobs = new ConcurrentHashMap<String, String>();
    private final ConcurrentMap<String, Object> projectLocks = new ConcurrentHashMap<String, Object>();

    public SingleSmartSplitService(FileStorageService fileStorageService,
                                   SmartSplitPipelineService pipelineService,
                                   @Qualifier("batchSplitExecutor") TaskExecutor taskExecutor) {
        this.fileStorageService = fileStorageService;
        this.pipelineService = pipelineService;
        this.taskExecutor = taskExecutor;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public SmartSplitJobResponse start(final String projectId, SmartSplitStartRequest request) {
        fileStorageService.loadMetadata(projectId);
        final String sourceMode = normalizeSourceMode(request == null ? null : request.getSourceMode());
        final String prompt = request == null ? "" : text(request.getPrompt());
        final boolean reuseDegreeAdjustmentEnabled = request == null
                || request.isReuseDegreeAdjustmentEnabled();
        synchronized (lockFor(projectId)) {
            String activeJobId = activeJobs.get(projectId);
            if (StringUtils.hasText(activeJobId)) {
                SmartSplitJobResponse active = loadState(projectId);
                if (activeJobId.equals(active.getJobId()) && active(active.getStatus())) {
                    return active;
                }
                activeJobs.remove(projectId, activeJobId);
            }

            final SmartSplitJobResponse state = new SmartSplitJobResponse();
            state.setJobId("SS" + LocalDateTime.now().format(JOB_TIME)
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT));
            state.setProjectId(projectId);
            state.setSourceMode(sourceMode);
            state.setReuseDegreeAdjustmentEnabled(reuseDegreeAdjustmentEnabled);
            state.setStatus("QUEUED");
            state.setStage("QUEUED");
            state.setStageMessage("EXCEL".equals(sourceMode) && !reuseDegreeAdjustmentEnabled
                    ? "Excel 载入与送审校验任务已进入队列"
                    : "AI 智能拆分任务已进入队列");
            state.setDeepSplitStatus(pipelineService.isDeepReviewEnabled() ? "PENDING" : "DISABLED");
            state.setProgressPercent(0);
            state.setCreatedAt(now());
            state.setUpdatedAt(state.getCreatedAt());
            saveState(state);
            activeJobs.put(projectId, state.getJobId());
            try {
                taskExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        SingleSmartSplitService.this.run(
                                state.getProjectId(), state.getJobId(), sourceMode, prompt,
                                reuseDegreeAdjustmentEnabled);
                    }
                });
            } catch (RuntimeException ex) {
                activeJobs.remove(projectId, state.getJobId());
                state.setStatus("FAILED");
                state.setStage("FAILED");
                state.setErrorMessage("智能拆分队列已满，请稍后重试：" + rootMessage(ex));
                state.setCompletedAt(now());
                state.setUpdatedAt(now());
                saveState(state);
                throw new BusinessException(state.getErrorMessage(), ex);
            }
            return state;
        }
    }

    public SmartSplitJobResponse get(String projectId) {
        fileStorageService.loadMetadata(projectId);
        synchronized (lockFor(projectId)) {
            SmartSplitJobResponse state = loadState(projectId);
            if (!pipelineService.isDeepReviewEnabled()
                    && !"SKIPPED_BY_USER".equals(state.getDeepSplitStatus())) {
                state.setDeepSplitStatus("DISABLED");
            }
            if (active(state.getStatus()) && !state.getJobId().equals(activeJobs.get(projectId))) {
                state.setStatus("FAILED");
                state.setStage("INTERRUPTED");
                state.setErrorMessage("服务重启中断了本次智能拆分，请重新执行。已完成的项目数据仍会保留。");
                state.setCompletedAt(now());
                completeCurrentStageTiming(state);
                state.setUpdatedAt(now());
                saveState(state);
            }
            return state;
        }
    }

    private void run(final String projectId,
                     final String jobId,
                     String sourceMode,
                     String prompt,
                     boolean reuseDegreeAdjustmentEnabled) {
        update(projectId, jobId, new StateMutation() {
            @Override
            public void apply(SmartSplitJobResponse state) {
                state.setStatus("RUNNING");
                state.setStage("STARTING");
                state.setStageMessage("EXCEL".equals(sourceMode) && !reuseDegreeAdjustmentEnabled
                        ? "正在载入 Excel 并保留人工复用度"
                        : "正在启动统一 AI 智能拆分流水线");
                state.setProgressPercent(2);
                if (!StringUtils.hasText(state.getStartedAt())) {
                    state.setStartedAt(now());
                }
            }
        });
        try {
            SmartSplitPipelineService.StageListener listener = new SmartSplitPipelineService.StageListener() {
                @Override
                public void onStage(final String stage, final int progressPercent, final String message) {
                    update(projectId, jobId, new StateMutation() {
                        @Override
                        public void apply(SmartSplitJobResponse state) {
                            state.setStage(stage);
                            state.setStageMessage(message);
                            state.setProgressPercent(Math.max(state.getProgressPercent(), progressPercent));
                            updateStageTiming(state, stage);
                        }
                    });
                }
            };
            final SmartSplitPipelineService.PipelineResult result = "EXCEL".equals(sourceMode)
                    ? pipelineService.runExistingRows(projectId, reuseDegreeAdjustmentEnabled, listener)
                    : pipelineService.runDocument(projectId, prompt, listener);
            update(projectId, jobId, new StateMutation() {
                @Override
                public void apply(SmartSplitJobResponse state) {
                    RuleValidationResponse validation = result.getValidation();
                    state.setStatus(result.isPartial() ? "PARTIAL" : "COMPLETED");
                    state.setStage("COMPLETED");
                    state.setStageMessage(!state.isReuseDegreeAdjustmentEnabled() && "EXCEL".equals(state.getSourceMode())
                            ? "已保留 Excel 人工拆分结果并完成规则初审"
                            : (result.isPartial()
                            ? "智能拆分部分完成，已进入人工审核"
                            : "智能拆分、复用度调整和规则初审已完成"));
                    state.setProgressPercent(100);
                    state.setDeepSplitJobId(result.getDeepSplitJobId());
                    state.setDeepSplitStatus(result.getDeepSplitStatus());
                    state.setDeepSuggestionCount(result.getDeepSuggestionCount());
                    state.setAppliedSuggestionCount(result.getAppliedSuggestionCount());
                    state.setSkippedSuggestionCount(result.getSkippedSuggestionCount());
                    state.setAppliedSuggestionIds(new ArrayList<String>(result.getAppliedSuggestionIds()));
                    state.setWarnings(new ArrayList<String>(result.getWarnings()));
                    state.setTotalRows(result.getRows() == null ? 0 : result.getRows().size());
                    state.setTotalCfp(result.getTotalCfp());
                    state.setRuleVersion(result.getRuleVersion());
                    if (validation != null) {
                        state.setErrorCount(validation.getErrorCount());
                        state.setRiskCount(validation.getRiskCount());
                        state.setStructureRiskCount(validation.getStructureRiskCount());
                    }
                    state.setCompletedAt(now());
                    completeCurrentStageTiming(state);
                }
            });
        } catch (final RuntimeException ex) {
            update(projectId, jobId, new StateMutation() {
                @Override
                public void apply(SmartSplitJobResponse state) {
                    state.setStatus("FAILED");
                    state.setStage("FAILED");
                    state.setStageMessage("AI 智能拆分未完成");
                    state.setErrorMessage(rootMessage(ex));
                    state.setCompletedAt(now());
                    completeCurrentStageTiming(state);
                }
            });
        } finally {
            activeJobs.remove(projectId, jobId);
        }
    }

    private void update(String projectId, String jobId, StateMutation mutation) {
        synchronized (lockFor(projectId)) {
            SmartSplitJobResponse state = loadState(projectId);
            if (!jobId.equals(state.getJobId())) {
                return;
            }
            mutation.apply(state);
            state.setUpdatedAt(now());
            saveState(state);
        }
    }

    private SmartSplitJobResponse loadState(String projectId) {
        Path path = statePath(projectId);
        if (!Files.exists(path)) {
            throw new BusinessException("当前项目还没有 AI 智能拆分任务。");
        }
        try {
            return objectMapper.readValue(path.toFile(), SmartSplitJobResponse.class);
        } catch (IOException ex) {
            throw new BusinessException("读取 AI 智能拆分进度失败。", ex);
        }
    }

    private void saveState(SmartSplitJobResponse state) {
        Path path = statePath(state.getProjectId());
        Path temporary = path.resolveSibling(path.getFileName().toString() + ".tmp." + UUID.randomUUID().toString());
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writeValue(temporary.toFile(), state);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new BusinessException("保存 AI 智能拆分进度失败。", ex);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Best-effort cleanup after an atomic state write attempt.
            }
        }
    }

    private Path statePath(String projectId) {
        return fileStorageService.resolveProjectDir(projectId).resolve("smart-split").resolve("state.json");
    }

    private Object lockFor(String projectId) {
        Object created = new Object();
        Object existing = projectLocks.putIfAbsent(projectId, created);
        return existing == null ? created : existing;
    }

    private String normalizeSourceMode(String sourceMode) {
        return "EXCEL".equalsIgnoreCase(text(sourceMode)) ? "EXCEL" : "DOCUMENT";
    }

    private void updateStageTiming(SmartSplitJobResponse state, String pipelineStage) {
        String visibleStage = visibleStage(pipelineStage);
        if (!StringUtils.hasText(visibleStage)) {
            if ("COMPLETED".equals(pipelineStage)) {
                completeCurrentStageTiming(state);
            }
            return;
        }
        String timestamp = now();
        for (java.util.Map.Entry<String, SmartSplitStageTiming> entry : state.getStageTimings().entrySet()) {
            if (!entry.getKey().equals(visibleStage)
                    && entry.getValue() != null
                    && StringUtils.hasText(entry.getValue().getStartedAt())
                    && !StringUtils.hasText(entry.getValue().getCompletedAt())) {
                entry.getValue().setCompletedAt(timestamp);
            }
        }
        SmartSplitStageTiming timing = state.getStageTimings().get(visibleStage);
        if (timing == null) {
            timing = new SmartSplitStageTiming();
            state.getStageTimings().put(visibleStage, timing);
        }
        if (!StringUtils.hasText(timing.getStartedAt())) {
            timing.setStartedAt(timestamp);
        }
    }

    private void completeCurrentStageTiming(SmartSplitJobResponse state) {
        String timestamp = now();
        for (SmartSplitStageTiming timing : state.getStageTimings().values()) {
            if (timing != null && StringUtils.hasText(timing.getStartedAt())
                    && !StringUtils.hasText(timing.getCompletedAt())) {
                timing.setCompletedAt(timestamp);
            }
        }
    }

    private String visibleStage(String pipelineStage) {
        if ("INPUT_COVERAGE_CHECK".equals(pipelineStage) || "AI_EXTRACTING".equals(pipelineStage)
                || "COSMIC_SPLITTING".equals(pipelineStage)
                || "LOADING_ROWS".equals(pipelineStage)) return "COSMIC";
        if ("DEEP_REVIEWING".equals(pipelineStage) || "DEEP_REVIEW_RETRYING".equals(pipelineStage)) {
            return "DEEP_REVIEW";
        }
        if ("APPLYING_DEEP_REVIEW".equals(pipelineStage)) return "APPLY_DEEP_REVIEW";
        if ("REUSE_ADJUSTING".equals(pipelineStage)) return "REUSE";
        if ("RULE_VALIDATING".equals(pipelineStage)) return "RULE_VALIDATION";
        return null;
    }

    private boolean active(String status) {
        return "QUEUED".equals(status) || "RUNNING".equals(status);
    }

    private String now() {
        return LocalDateTime.now().format(DISPLAY_TIME);
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current == null ? "" : current.getMessage();
        return StringUtils.hasText(message) ? message : "未知错误";
    }

    private interface StateMutation {
        void apply(SmartSplitJobResponse state);
    }
}
