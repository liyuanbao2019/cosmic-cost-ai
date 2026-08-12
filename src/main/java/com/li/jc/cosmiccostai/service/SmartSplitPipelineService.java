package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.DeepSplitJobResponse;
import com.li.jc.cosmiccostai.dto.DeepSplitRequest;
import com.li.jc.cosmiccostai.dto.ReuseDegreeAdjustmentResponse;
import com.li.jc.cosmiccostai.dto.ReviewResponse;
import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One authoritative project-level pipeline shared by single-document and batch
 * orchestration. The UI may present it as one "AI smart split" step while the
 * quality-sensitive stages remain independent internally.
 */
@Service
public class SmartSplitPipelineService {

    private final RequirementCoverageService requirementCoverageService;
    private final AiExtractionService aiExtractionService;
    private final CosmicAnalysisService cosmicAnalysisService;
    private final ReviewService reviewService;
    private final DeepSplitAiService deepSplitAiService;
    private final DeepSplitSuggestionApplyService suggestionApplyService;
    private final ReuseDegreeAiAdjustmentService reuseDegreeAiAdjustmentService;
    private final RuleValidationService ruleValidationService;
    private final boolean deepReviewEnabled;
    private final long deepReviewWaitMillis;
    private final int deepReviewRetryRounds;

    public SmartSplitPipelineService(RequirementCoverageService requirementCoverageService,
                                     AiExtractionService aiExtractionService,
                                     CosmicAnalysisService cosmicAnalysisService,
                                     ReviewService reviewService,
                                     DeepSplitAiService deepSplitAiService,
                                     DeepSplitSuggestionApplyService suggestionApplyService,
                                     ReuseDegreeAiAdjustmentService reuseDegreeAiAdjustmentService,
                                     RuleValidationService ruleValidationService,
                                     @Value("${cosmic.ai.smart-split.deep-review-enabled:false}") boolean deepReviewEnabled,
                                     @Value("${cosmic.ai.smart-split.deep-review-wait-seconds:1800}") long deepReviewWaitSeconds,
                                     @Value("${cosmic.ai.smart-split.deep-review-retry-rounds:2}") int deepReviewRetryRounds) {
        this.requirementCoverageService = requirementCoverageService;
        this.aiExtractionService = aiExtractionService;
        this.cosmicAnalysisService = cosmicAnalysisService;
        this.reviewService = reviewService;
        this.deepSplitAiService = deepSplitAiService;
        this.suggestionApplyService = suggestionApplyService;
        this.reuseDegreeAiAdjustmentService = reuseDegreeAiAdjustmentService;
        this.ruleValidationService = ruleValidationService;
        this.deepReviewEnabled = deepReviewEnabled;
        this.deepReviewWaitMillis = Math.max(60, deepReviewWaitSeconds) * 1000L;
        this.deepReviewRetryRounds = Math.max(0, deepReviewRetryRounds);
    }

    public PipelineResult runDocument(String projectId, String editedPrompt, StageListener listener) {
        notify(listener, "AI_EXTRACTING", 8, "正在识别需求功能并生成结构化功能清单");
        requirementCoverageService.ensureReadyForExtraction(projectId);
        if (StringUtils.hasText(editedPrompt)) {
            aiExtractionService.extractWithPrompt(projectId, editedPrompt.trim());
        } else {
            aiExtractionService.extract(projectId);
        }

        notify(listener, "COSMIC_SPLITTING", 28, "正在生成 COSMIC 功能过程和 E/R/W/X 初稿");
        cosmicAnalysisService.analyze(projectId);
        return finishFromCurrentRows(projectId, listener);
    }

    public PipelineResult runExistingRows(String projectId, StageListener listener) {
        notify(listener, "LOADING_ROWS", 25, "已载入拆分表，准备执行统一智能校准");
        return finishFromCurrentRows(projectId, listener);
    }

    private PipelineResult finishFromCurrentRows(String projectId, StageListener listener) {
        ReviewResponse review = reviewService.getReview(projectId);
        List<FunctionSplitRow> rows = review.getSplitRows();
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException("智能拆分未取得可审核的功能点拆分行。");
        }

        PipelineResult result = new PipelineResult();
        if (deepReviewEnabled) {
            notify(listener, "DEEP_REVIEWING", 42, "正在结合原文证据执行 AI 深度结构审核");
            DeepSplitRequest request = new DeepSplitRequest();
            request.setRows(rows);
            DeepSplitJobResponse deepJob = deepSplitAiService.start(projectId, request);
            deepJob = awaitDeepReview(projectId, deepJob, listener);

            result.deepSplitJobId = deepJob.getJobId();
            result.deepSplitStatus = deepJob.getStatus();
            result.deepSuggestionCount = deepJob.getSuggestions() == null ? 0 : deepJob.getSuggestions().size();
            result.warnings.addAll(deepJob.getWarnings() == null
                    ? Collections.<String>emptyList() : deepJob.getWarnings());

            if ("FAILED".equals(deepJob.getStatus()) && result.deepSuggestionCount == 0) {
                throw new BusinessException("AI 深度审核全部失败，已停止后续自动调整，请重新执行智能拆分。");
            }

            notify(listener, "APPLYING_DEEP_REVIEW", 68, "正在自动采纳有证据、无冲突的深度审核建议");
            DeepSplitSuggestionApplyService.ApplyResult applied = suggestionApplyService.apply(
                    rows, deepJob.getSuggestions());
            result.appliedSuggestionCount = applied.getApplied();
            result.skippedSuggestionCount = applied.getSkipped();
            result.appliedSuggestionIds.addAll(applied.getAppliedSuggestionIds());
            if (applied.getApplied() > 0) {
                RuleValidationResponse baselineGate = ruleValidationService.validate(rows);
                RuleValidationResponse applicationGate = ruleValidationService.validate(applied.getRows());
                if (applicationGate.getErrorCount() <= baselineGate.getErrorCount()) {
                    ReviewResponse saved = reviewService.saveSmartSplitAdjustedReview(projectId, applied.getRows());
                    rows = saved.getSplitRows();
                } else {
                    result.warnings.add("深度审核建议增加了确定性错误，已保留原始拆分表并转入最终规则初审。");
                    result.skippedSuggestionCount += result.appliedSuggestionCount;
                    result.appliedSuggestionCount = 0;
                    result.appliedSuggestionIds.clear();
                }
            }
            result.partial = !"COMPLETED".equals(deepJob.getStatus());
            if (result.partial) {
                result.warnings.add("AI 深度审核仅部分完成；已完成建议已处理，失败批次保留为人工风险提示。");
            }
        } else {
            result.deepSplitStatus = "DISABLED";
        }

        notify(listener, "REUSE_ADJUSTING", deepReviewEnabled ? 80 : 62,
                "正在按完整拆分表统一调整新增、复用和利旧");
        ReuseDegreeAdjustmentResponse reuse = reuseDegreeAiAdjustmentService.adjust(projectId, rows);
        rows = reuse.getRows();

        notify(listener, "RULE_VALIDATING", deepReviewEnabled ? 94 : 88,
                "正在执行最终确定性规则校验并生成风险提示");
        RuleValidationResponse validation = ruleValidationService.validate(rows);
        result.rows = rows;
        result.totalCfp = totalCfp(rows);
        result.validation = validation;
        result.ruleVersion = validation.getRuleVersion();
        notify(listener, "COMPLETED", 100, result.partial
                ? "智能拆分部分完成，已进入人工审核"
                : "智能拆分、复用度调整和规则初审已完成");
        return result;
    }

    public boolean isDeepReviewEnabled() {
        return deepReviewEnabled;
    }

    private DeepSplitJobResponse awaitDeepReview(String projectId,
                                                  DeepSplitJobResponse initial,
                                                  StageListener listener) {
        DeepSplitJobResponse job = waitUntilTerminal(projectId, initial, listener);
        int retried = 0;
        while (job.getFailedChunks() > 0 && retried < deepReviewRetryRounds) {
            retried++;
            notify(listener, "DEEP_REVIEW_RETRYING", Math.max(45, Math.min(64, job.getProgressPercent())),
                    "深度审核存在失败批次，正在自动拆小重试（" + retried + "/" + deepReviewRetryRounds + "）");
            job = deepSplitAiService.retryFailed(projectId, job.getJobId());
            job = waitUntilTerminal(projectId, job, listener);
        }
        return job;
    }

    private DeepSplitJobResponse waitUntilTerminal(String projectId,
                                                    DeepSplitJobResponse initial,
                                                    StageListener listener) {
        long deadline = System.currentTimeMillis() + deepReviewWaitMillis;
        DeepSplitJobResponse current = initial;
        while (!terminal(current.getStatus())) {
            if (System.currentTimeMillis() >= deadline) {
                throw new BusinessException("AI 深度审核等待超时，任务仍保留在后台，可稍后重新进入项目查看。");
            }
            try {
                Thread.sleep(600L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new BusinessException("AI 智能拆分任务已被中断。", ex);
            }
            current = deepSplitAiService.get(projectId, current.getJobId());
            int progress = 42 + (int) Math.round(Math.max(0, Math.min(100, current.getProgressPercent())) * 0.24D);
            notify(listener, "DEEP_REVIEWING", progress,
                    "AI 深度审核 " + current.getCompletedChunks() + "/" + current.getTotalChunks()
                            + " 批，失败 " + current.getFailedChunks() + " 批");
        }
        return current;
    }

    private boolean terminal(String status) {
        return "COMPLETED".equals(status) || "PARTIAL".equals(status) || "FAILED".equals(status);
    }

    private void notify(StageListener listener, String stage, int progress, String message) {
        if (listener != null) {
            listener.onStage(stage, progress, message);
        }
    }

    private double totalCfp(List<FunctionSplitRow> rows) {
        double total = 0D;
        for (FunctionSplitRow row : rows == null ? Collections.<FunctionSplitRow>emptyList() : rows) {
            total += row.getCfp();
        }
        return total;
    }

    public interface StageListener {
        void onStage(String stage, int progressPercent, String message);
    }

    public static class PipelineResult {
        private List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        private String deepSplitJobId;
        private String deepSplitStatus;
        private int deepSuggestionCount;
        private int appliedSuggestionCount;
        private int skippedSuggestionCount;
        private double totalCfp;
        private RuleValidationResponse validation;
        private String ruleVersion;
        private boolean partial;
        private final List<String> appliedSuggestionIds = new ArrayList<String>();
        private final List<String> warnings = new ArrayList<String>();

        public List<FunctionSplitRow> getRows() { return rows; }
        public String getDeepSplitJobId() { return deepSplitJobId; }
        public String getDeepSplitStatus() { return deepSplitStatus; }
        public int getDeepSuggestionCount() { return deepSuggestionCount; }
        public int getAppliedSuggestionCount() { return appliedSuggestionCount; }
        public int getSkippedSuggestionCount() { return skippedSuggestionCount; }
        public double getTotalCfp() { return totalCfp; }
        public RuleValidationResponse getValidation() { return validation; }
        public String getRuleVersion() { return ruleVersion; }
        public boolean isPartial() { return partial; }
        public List<String> getAppliedSuggestionIds() { return appliedSuggestionIds; }
        public List<String> getWarnings() { return warnings; }
    }
}
