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
        assertTrue(html.contains("重新执行深度审核"));
        assertTrue(html.contains("COSMIC 初拆 → AI 深度审核 → 安全建议采纳 → 复用度调整 → 规则初审"));
        assertTrue(html.contains("id=\"smartSplitDialogProgressBar\""));
        assertTrue(html.contains("id=\"smartStageCosmic\""));
        assertTrue(html.contains("id=\"smartStageDeepReview\""));
        assertTrue(html.contains("id=\"smartStageApply\""));
        assertTrue(html.contains("id=\"smartStageReuse\""));
        assertTrue(html.contains("id=\"smartStageRules\""));

        int clickStart = html.indexOf("function handleStepClick");
        int clickEnd = html.indexOf("function showRuleValidationPanel", clickStart);
        String clickHandler = html.substring(clickStart, clickEnd);
        assertTrue(clickHandler.contains("openReviewStageDialog()"));
        assertFalse(clickHandler.contains("showAiPrompt()"));
    }
}
