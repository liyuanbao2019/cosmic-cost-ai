package com.li.jc.cosmiccostai.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartSplitUiRegressionTest {

    @Test
    void singleDemandFlowExposesOneSmartSplitNodeAndRunsPersistedWorkflow() throws Exception {
        String html = new String(Files.readAllBytes(
                Paths.get("src/main/resources/static/index.html")), StandardCharsets.UTF_8);

        assertTrue(html.contains("splitMode === \"excel\" ? [\"ai\", \"review\"]"
                + " : [\"upload\", \"ai\", \"review\"]"));
        assertTrue(html.contains("<span class=\"step-label\">上传与解析</span>"));
        assertTrue(html.contains("<span class=\"step-label\">AI 智能拆分</span>"));
        assertFalse(html.contains("id=\"step-cosmic\""));
        assertFalse(html.contains("id=\"step-reuse\""));
        assertFalse(html.contains("id=\"step-precheck\""));
        assertTrue(html.contains("/smart-split"));
        assertTrue(html.contains("id=\"deepSplitBtn\" type=\"button\" class=\"deep-split-primary hidden\""));
        assertTrue(html.contains("COSMIC 初拆 → 复用度调整 → 规则初审"));
        assertTrue(html.contains("AI 深度审核已暂时关闭，流程不会发起深审模型请求"));
        assertTrue(html.contains("id=\"smartSplitDialogProgressBar\""));
        assertTrue(html.contains("id=\"smartStageCosmic\""));
        assertTrue(html.contains("id=\"smartStageDeepReview\""));
        assertTrue(html.contains("id=\"smartStageApply\""));
        assertTrue(html.contains("id=\"smartStageReuse\""));
        assertTrue(html.contains("id=\"smartStageRules\""));
        assertTrue(html.contains("id=\"smartSplitDialogStartedAt\"")
                && html.contains("id=\"smartSplitDialogCompletedAt\""));
        assertTrue(html.contains("data-stage-timing=\"COSMIC\"")
                && html.contains("data-stage-timing=\"REUSE\"")
                && html.contains("data-stage-timing=\"RULE_VALIDATION\""));
        assertTrue(html.contains("renderSmartSplitStageTimes(job)"));
        assertTrue(html.contains("const normalized = value == null ? \"\" : String(value).trim();"));
        assertFalse(html.contains("const normalized = text(value);"));

        int clickStart = html.indexOf("function handleStepClick");
        int clickEnd = html.indexOf("function showRuleValidationPanel", clickStart);
        String clickHandler = html.substring(clickStart, clickEnd);
        assertTrue(clickHandler.contains("openReviewStageDialog()"));
        assertFalse(clickHandler.contains("showAiPrompt()"));
    }
}
