package com.li.jc.cosmiccostai.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchSplitUiRegressionTest {

    @Test
    void multiDocumentInputRequiresPerDocumentConfirmationBeforeStart() throws Exception {
        String html = new String(Files.readAllBytes(
                Paths.get("src/main/resources/static/index.html")), StandardCharsets.UTF_8);
        String css = new String(Files.readAllBytes(
                Paths.get("src/main/resources/static/app.css")), StandardCharsets.UTF_8);
        assertTrue(html.contains("id=\"batchRequirementMaterials\""));
        assertTrue(html.contains("产物 1 · 项目整合送审材料")
                && html.contains("产物 2 · 项目全量单需求送审材料总包"));
        assertTrue(html.contains("产物 1：下载项目整合送审包")
                && html.contains("产物 2：下载项目全量单需求送审包"));
        assertTrue(html.contains("Number(job.completed || 0) === Number(job.total || 0)"));
        assertTrue(html.contains("function exportBatchRequirementMaterials()"));
        assertTrue(html.contains("/requirement-materials"));
        assertTrue(html.contains("id=\"batchSummaryBtn\"")
                && html.contains("id=\"batchSummaryDialogOverlay\""));
        assertTrue(html.contains("function openBatchSummaryDialog()")
                && html.contains("/summary"));
        assertTrue(html.contains("id=\"batchSummaryDownloadBtn\"")
                && html.contains("function downloadBatchSummaryExcel()")
                && html.contains("/summary/download"));
        assertTrue(html.contains("summary.totalCfp")
                && html.contains("summary.functionProcessCount"));

        assertTrue(html.contains("id=\"fileInput\" type=\"file\" accept=\".doc,.docx\" multiple"));
        assertTrue(html.contains("let selectedDocumentFiles = []"));
        assertTrue(html.contains("selectedDocumentFiles.push(file)"));
        assertTrue(html.contains("input.value = \"\"")
                && html.contains("selectedDocumentFiles.slice()"));
        assertTrue(html.contains("可分多次从不同目录追加")
                && html.contains("已累计选择 "));
        assertFalse(html.contains("Array.prototype.slice.call($(\"fileInput\").files || [])"));
        assertTrue(html.contains("id=\"batchStartBtn\"") && html.contains("等待全部确认"));
        assertTrue(html.contains("Number(job.confirmed || 0) > 0")
                && html.contains("preparationBlockers === 0"));
        assertTrue(html.contains("/ai-input"));
        assertTrue(html.contains("/start"));
        assertTrue(html.contains("id=\"splitLevel\"") && html.contains("value=\"PROJECT\">项目级"));
        assertTrue(html.contains("selectedSplitLevel() === \"PROJECT\""));
        assertTrue(html.contains("COSMIC 初拆 → 复用度调整 → 规则初审"));
        assertTrue(html.contains("id=\"batchProjectMaterials\""));
        assertTrue(html.contains("/materials/retry") && html.contains("/materials/"));
        assertTrue(html.contains("status === \"BLOCKED\""));
        assertTrue(html.contains("项目级送审材料不会生成，请重试失败项"));
        assertTrue(html.contains("失败文档已独立重新进入处理队列"));
        assertTrue(html.contains("已成功结果保留"));
        assertFalse(html.contains("if ($(\"splitLevel\") && job.splitLevel)"));
        assertTrue(html.contains("currentBatchSplitJob && currentBatchSplitJob.splitLevel"));
        assertTrue(html.contains("id=\"batchPreparingCount\""));
        assertTrue(html.contains("id=\"batchParseQueuedCount\""));
        assertTrue(html.contains("id=\"batchQueuedCount\""));
        assertTrue(html.contains("status === \"PARSE_QUEUED\""));
        assertTrue(html.contains("$(\"batchRunningCount\").textContent = job.running || 0"));
        assertTrue(html.contains("$(\"batchRetryBtn\").classList.toggle(\"hidden\", !(job.failed > 0))"));
        assertFalse(html.contains("job.failed > 0 && !active"));
        assertFalse(html.contains("重启服务后排队任务会自动续跑"));
        assertTrue(html.contains("重启后不会自动恢复任务"));
        assertTrue(html.contains("恢复中断项"));
        assertTrue(html.contains("RESTART_INTERRUPTED"));
        assertTrue(html.contains("跨批次公平调度"));
        assertTrue(html.contains("id=\"batchProjectGroupBadge\""));
        assertTrue(html.contains("添加已拆分需求") && html.contains("上传并添加新需求"));
        assertTrue(html.contains("projectGroupAddDialogOverlay")
                && html.contains("submitExistingRequirementsToProjectGroup"));
        assertFalse(html.contains("/projects.html?targetBatchId="));
        assertTrue(html.contains("/rerun") && html.contains("重新拆分当前需求"));
        assertTrue(html.contains("id=\"batchSelectedItemError\"")
                && html.contains("copyBatchSelectedItemError"));
        assertTrue(html.contains("item.confirmedAt || Number(item.inputLength || 0) > 0"));
        assertTrue(css.contains(".review-stage-dialog .review-stage-grid")
                && css.contains("repeat(auto-fit, minmax(220px, 1fr))"));
        assertTrue(css.contains(".review-stage-dialog .smart-split-stage-times")
                && css.contains("grid-template-columns: repeat(2, minmax(0, 1fr))")
                && css.contains("flex-direction: column")
                && css.contains("text-overflow: ellipsis")
                && css.contains(".review-stage-dialog .smart-split-stage-times b"));
    }
}
