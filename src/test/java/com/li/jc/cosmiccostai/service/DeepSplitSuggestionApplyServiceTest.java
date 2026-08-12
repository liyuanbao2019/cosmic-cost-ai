package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.DeepSplitSuggestion;
import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeepSplitSuggestionApplyServiceTest {

    @Test
    void appliesOnlyNonConflictingStructuralSuggestionsAndLeavesReuseToDedicatedStage() {
        RuleValidationService validationService = mock(RuleValidationService.class);
        when(validationService.validate(anyList())).thenReturn(validation(0));
        DeepSplitSuggestionApplyService service = new DeepSplitSuggestionApplyService(validationService);

        FunctionSplitRow first = row("原功能A", "E");
        FunctionSplitRow second = row("原功能B", "X");
        DeepSplitSuggestion structural = suggestion("S1", "ADJUST_MOVEMENT_STRUCTURE", 1,
                row("调整后功能A", "E"));
        DeepSplitSuggestion conflicting = suggestion("S2", "SPLIT_FUNCTION", 1,
                row("不应覆盖", "E"));
        DeepSplitSuggestion reuse = suggestion("S3", "ADJUST_REUSE_CLASSIFICATION", 2,
                row("复用建议留给后续阶段", "X"));

        DeepSplitSuggestionApplyService.ApplyResult result = service.apply(
                Arrays.asList(first, second), Arrays.asList(structural, conflicting, reuse));

        assertEquals(1, result.getApplied());
        assertEquals(2, result.getSkipped());
        assertEquals(Collections.singletonList("S1"), result.getAppliedSuggestionIds());
        assertEquals("调整后功能A", result.getRows().get(0).getFunctionProcess());
        assertEquals("原功能B", result.getRows().get(1).getFunctionProcess());
        assertNotSame(first, result.getRows().get(0));
    }

    @Test
    void rejectsSuggestionThatIntroducesMoreDeterministicErrors() {
        RuleValidationService validationService = mock(RuleValidationService.class);
        when(validationService.validate(anyList())).thenReturn(validation(0), validation(1));
        DeepSplitSuggestionApplyService service = new DeepSplitSuggestionApplyService(validationService);

        FunctionSplitRow original = row("原功能", "E");
        DeepSplitSuggestion unsafe = suggestion("S1", "ADJUST_MOVEMENT_STRUCTURE", 1,
                row("错误替换", "R"));

        DeepSplitSuggestionApplyService.ApplyResult result = service.apply(
                Collections.singletonList(original), Collections.singletonList(unsafe));

        assertEquals(0, result.getApplied());
        assertEquals(1, result.getSkipped());
        assertEquals("原功能", result.getRows().get(0).getFunctionProcess());
    }

    @Test
    void overlappingSuggestionsPreferHighestConfidenceInsteadOfAsyncArrivalOrder() {
        RuleValidationService validationService = mock(RuleValidationService.class);
        when(validationService.validate(anyList())).thenReturn(validation(0));
        DeepSplitSuggestionApplyService service = new DeepSplitSuggestionApplyService(validationService);
        DeepSplitSuggestion low = suggestion("LOW", "ADJUST_MOVEMENT_STRUCTURE", 1,
                row("低置信度", "E"));
        low.setConfidence(0.81D);
        DeepSplitSuggestion high = suggestion("HIGH", "SPLIT_FUNCTION", 1,
                row("高置信度", "E"));
        high.setConfidence(0.97D);

        DeepSplitSuggestionApplyService.ApplyResult result = service.apply(
                Collections.singletonList(row("原功能", "E")), Arrays.asList(low, high));

        assertEquals(Collections.singletonList("HIGH"), result.getAppliedSuggestionIds());
        assertEquals("高置信度", result.getRows().get(0).getFunctionProcess());
    }

    private RuleValidationResponse validation(int errors) {
        RuleValidationResponse response = new RuleValidationResponse();
        response.setErrorCount(errors);
        return response;
    }

    private DeepSplitSuggestion suggestion(String id, String operation, int target, FunctionSplitRow replacement) {
        DeepSplitSuggestion suggestion = new DeepSplitSuggestion();
        suggestion.setId(id);
        suggestion.setOperation(operation);
        suggestion.setTargetRowNumbers(Collections.singletonList(target));
        suggestion.setReplacementRows(Collections.singletonList(replacement));
        return suggestion;
    }

    private FunctionSplitRow row(String process, String movement) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setProjectName("项目");
        row.setLevel1Module("一级模块");
        row.setFunctionProcess(process);
        row.setSubProcessDescription(process + "子过程");
        row.setMovementType(movement);
        row.setDataGroup(process + "数据");
        row.setDataAttributes("编号");
        row.setReuseDegree("新增");
        row.setCfp(1D);
        return row;
    }
}
