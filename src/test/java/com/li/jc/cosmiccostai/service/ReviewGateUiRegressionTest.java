package com.li.jc.cosmiccostai.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewGateUiRegressionTest {

    @Test
    void deepSplitApplyBlocksErrorsButNotRemainingStructureRisks() throws Exception {
        String function = functionSource("async function applyDeepSplitSuggestion", "function currentIndexForDeepSplitOriginalRow");

        assertTrue(function.contains("if ((gate.errorCount || 0) > 0)"));
        assertFalse(function.contains("(gate.structureRiskCount || 0) > 0"));
        assertFalse(function.contains("textOr(item.suggestedReuseDegree"));
        assertTrue(function.contains("可继续逐条采纳或带风险标记导出"));
    }

    @Test
    void exportConfirmsErrorsAndCarriesAllRisksIntoColoredWorkbook() throws Exception {
        String function = functionSource("async function exportReviewMaterials", "function markRunningExportFailed");

        assertTrue(function.contains("exportErrorCount > 0 && !window.confirm"));
        assertTrue(function.contains("仍要生成送审材料"));
        assertTrue(function.contains("均允许导出"));
        assertFalse(function.contains("无法导出送审材料"));
    }

    private String functionSource(String startMarker, String endMarker) throws Exception {
        String html = new String(Files.readAllBytes(
                Paths.get("src/main/resources/static/index.html")), StandardCharsets.UTF_8);
        int start = html.indexOf(startMarker);
        int end = html.indexOf(endMarker, start);
        assertTrue(start >= 0, "missing start marker: " + startMarker);
        assertTrue(end > start, "missing end marker: " + endMarker);
        return html.substring(start, end);
    }
}
