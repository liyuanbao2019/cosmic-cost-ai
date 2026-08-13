package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.DeepSplitJobResponse;
import com.li.jc.cosmiccostai.dto.DeepSplitRequest;
import com.li.jc.cosmiccostai.dto.DeepSplitSuggestion;
import com.li.jc.cosmiccostai.dto.ReuseDegreeAdjustmentResponse;
import com.li.jc.cosmiccostai.dto.ReviewResponse;
import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SmartSplitPipelineServiceTest {

    @Test
    void documentPipelineRunsAllQualityStagesInAuthoritativeOrder() {
        RequirementCoverageService coverage = mock(RequirementCoverageService.class);
        AiExtractionService extraction = mock(AiExtractionService.class);
        CosmicAnalysisService cosmic = mock(CosmicAnalysisService.class);
        ReviewService review = mock(ReviewService.class);
        DeepSplitAiService deep = mock(DeepSplitAiService.class);
        ReuseDegreeAiAdjustmentService reuse = mock(ReuseDegreeAiAdjustmentService.class);
        RuleValidationService validation = mock(RuleValidationService.class);
        DeepSplitSuggestionApplyService apply = new DeepSplitSuggestionApplyService(validation);

        FunctionSplitRow row = row();
        ReviewResponse reviewResponse = new ReviewResponse();
        reviewResponse.setSplitRows(Collections.singletonList(row));
        when(review.getReview("P1")).thenReturn(reviewResponse);
        DeepSplitJobResponse deepResponse = new DeepSplitJobResponse();
        deepResponse.setJobId("D1");
        deepResponse.setStatus("COMPLETED");
        DeepSplitSuggestion suggestion = new DeepSplitSuggestion();
        suggestion.setId("S1");
        suggestion.setOperation("ADJUST_MOVEMENT_STRUCTURE");
        suggestion.setTargetRowNumbers(Collections.singletonList(1));
        suggestion.setReplacementRows(Collections.singletonList(row));
        deepResponse.setSuggestions(Collections.singletonList(suggestion));
        when(deep.start(eq("P1"), any(DeepSplitRequest.class))).thenReturn(deepResponse);
        when(review.saveSmartSplitAdjustedReview(eq("P1"), any())).thenReturn(reviewResponse);
        ReuseDegreeAdjustmentResponse reuseResponse = new ReuseDegreeAdjustmentResponse();
        reuseResponse.setRows(Collections.singletonList(row));
        when(reuse.adjust(eq("P1"), any())).thenReturn(reuseResponse);
        RuleValidationResponse validationResponse = new RuleValidationResponse();
        validationResponse.setRuleVersion("R-test");
        when(validation.validate(any())).thenReturn(validationResponse);

        SmartSplitPipelineService service = new SmartSplitPipelineService(
                coverage, extraction, cosmic, review, deep, apply, reuse, validation, true, 60, 2);
        final List<String> stages = new ArrayList<String>();
        SmartSplitPipelineService.PipelineResult result = service.runDocument(
                "P1", "edited prompt", new SmartSplitPipelineService.StageListener() {
                    @Override
                    public void onStage(String stage, int progressPercent, String message) {
                        stages.add(stage);
                    }
                });

        InOrder order = inOrder(coverage, extraction, cosmic, review, deep, reuse, validation);
        order.verify(coverage).ensureReadyForExtraction("P1");
        order.verify(extraction).extractWithPrompt("P1", "edited prompt");
        order.verify(cosmic).analyze("P1");
        order.verify(review).getReview("P1");
        order.verify(deep).start(eq("P1"), any(DeepSplitRequest.class));
        order.verify(review).saveSmartSplitAdjustedReview(eq("P1"), any());
        order.verify(reuse).adjust(eq("P1"), any());
        order.verify(validation).validate(any());
        assertEquals("D1", result.getDeepSplitJobId());
        assertEquals(1, result.getAppliedSuggestionCount());
        assertEquals("R-test", result.getRuleVersion());
        assertFalse(result.isPartial());
        assertTrue(stages.indexOf("INPUT_COVERAGE_CHECK") < stages.indexOf("AI_EXTRACTING"));
        assertTrue(stages.indexOf("DEEP_REVIEWING") < stages.indexOf("REUSE_ADJUSTING"));
        assertTrue(stages.indexOf("REUSE_ADJUSTING") < stages.indexOf("RULE_VALIDATING"));
        assertEquals("COMPLETED", stages.get(stages.size() - 1));
        verify(extraction).extractWithPrompt("P1", "edited prompt");
    }

    @Test
    void disabledDeepReviewSkipsModelAndSuggestionApplicationInSharedPipeline() {
        RequirementCoverageService coverage = mock(RequirementCoverageService.class);
        AiExtractionService extraction = mock(AiExtractionService.class);
        CosmicAnalysisService cosmic = mock(CosmicAnalysisService.class);
        ReviewService review = mock(ReviewService.class);
        DeepSplitAiService deep = mock(DeepSplitAiService.class);
        ReuseDegreeAiAdjustmentService reuse = mock(ReuseDegreeAiAdjustmentService.class);
        RuleValidationService validation = mock(RuleValidationService.class);
        DeepSplitSuggestionApplyService apply = new DeepSplitSuggestionApplyService(validation);

        FunctionSplitRow row = row();
        ReviewResponse reviewResponse = new ReviewResponse();
        reviewResponse.setSplitRows(Collections.singletonList(row));
        when(review.getReview("P1")).thenReturn(reviewResponse);
        ReuseDegreeAdjustmentResponse reuseResponse = new ReuseDegreeAdjustmentResponse();
        reuseResponse.setRows(Collections.singletonList(row));
        when(reuse.adjust(eq("P1"), any())).thenReturn(reuseResponse);
        RuleValidationResponse validationResponse = new RuleValidationResponse();
        validationResponse.setRuleVersion("R-without-deep-review");
        when(validation.validate(any())).thenReturn(validationResponse);

        SmartSplitPipelineService service = new SmartSplitPipelineService(
                coverage, extraction, cosmic, review, deep, apply, reuse, validation, false, 60, 2);
        final List<String> stages = new ArrayList<String>();
        SmartSplitPipelineService.PipelineResult result = service.runDocument(
                "P1", "", new SmartSplitPipelineService.StageListener() {
                    @Override
                    public void onStage(String stage, int progressPercent, String message) {
                        stages.add(stage);
                    }
                });

        verifyNoInteractions(deep);
        verify(review, never()).saveSmartSplitAdjustedReview(eq("P1"), any());
        verify(reuse).adjust(eq("P1"), any());
        assertFalse(stages.contains("DEEP_REVIEWING"));
        assertFalse(stages.contains("APPLYING_DEEP_REVIEW"));
        assertTrue(stages.indexOf("REUSE_ADJUSTING") < stages.indexOf("RULE_VALIDATING"));
        assertEquals("DISABLED", result.getDeepSplitStatus());
        assertEquals(0, result.getDeepSuggestionCount());
        assertFalse(result.isPartial());
        assertEquals("R-without-deep-review", result.getRuleVersion());
    }

    private FunctionSplitRow row() {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setProjectName("项目");
        row.setLevel1Module("模块");
        row.setFunctionProcess("新增数据");
        row.setSubProcessDescription("发起新增数据请求");
        row.setMovementType("E");
        row.setDataGroup("新增数据请求");
        row.setDataAttributes("编号");
        row.setReuseDegree("新增");
        row.setCfp(1D);
        return row;
    }
}
