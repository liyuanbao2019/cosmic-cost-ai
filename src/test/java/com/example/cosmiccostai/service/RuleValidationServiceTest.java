package com.example.cosmiccostai.service;

import com.example.cosmiccostai.dto.RuleValidationResponse;
import com.example.cosmiccostai.model.FunctionSplitRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void completeProcessKeywordHitDoesNotCreateIssueWhenStructureIsComplete() {
        RuleValidationService service = new RuleValidationService();

        FunctionSplitRow entry = row("process-b", "E", "request", "id", "\u65b0\u589e");
        entry.setSubProcessDescription("\u63a5\u6536\u67e5\u8be2\u6761\u4ef6");
        FunctionSplitRow read = row("process-b", "R", "source", "amount", "\u65b0\u589e");
        read.setSubProcessDescription("\u8ba1\u7b97\u6c47\u603b\u6570\u636e");
        FunctionSplitRow exit = row("process-b", "X", "result", "total", "\u65b0\u589e");
        exit.setSubProcessDescription("\u8f93\u51fa\u6c47\u603b\u7ed3\u679c");

        RuleValidationResponse response = service.validate(Arrays.asList(entry, read, exit));

        assertEquals(0, response.getErrorCount());
        assertEquals(0, response.getRiskCount());
        assertEquals(0, response.getSuggestionCount());
        assertEquals(0, response.getExpressionConfirmCount());
        assertEquals(0, response.getIssueCount());
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
