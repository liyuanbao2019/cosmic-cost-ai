package com.example.cosmiccostai.service;

import com.example.cosmiccostai.dto.RuleValidationResponse;
import com.example.cosmiccostai.model.FunctionSplitRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleValidationServiceTest {

    @Test
    void validatesRequiredKeywordAndProcessStructureRules() {
        RuleValidationService service = new RuleValidationService();

        FunctionSplitRow first = row("process-a", "E", "request", "id", "\u65b0\u589e");
        first.setSubProcessDescription("\u89e3\u6790\u7533\u8bf7\u4fe1\u606f");
        FunctionSplitRow second = row("process-a", "E", "", "status", "\u65b0\u589e");

        RuleValidationResponse response = service.validate(Arrays.asList(first, second));

        assertTrue(response.getIssueCount() >= 3);
        assertTrue(response.getErrorCount() >= 1);
        assertTrue(response.getRiskCount() >= 2);
    }

    private FunctionSplitRow row(String process, String movement, String dataGroup, String dataAttributes, String reuse) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setLevel1Module("module-a");
        row.setFunctionProcess(process);
        row.setSubProcessDescription("sub-process");
        row.setMovementType(movement);
        row.setDataGroup(dataGroup);
        row.setDataAttributes(dataAttributes);
        row.setReuseDegree(reuse);
        return row;
    }
}
