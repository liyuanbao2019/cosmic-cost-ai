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

        assertTrue(html.contains("id=\"fileInput\" type=\"file\" accept=\".doc,.docx\" multiple"));
        assertTrue(html.contains("id=\"batchStartBtn\"") && html.contains("等待全部确认"));
        assertTrue(html.contains("job.confirmed === job.total"));
        assertTrue(html.contains("/ai-input"));
        assertTrue(html.contains("/start"));
        assertTrue(html.contains("id=\"splitLevel\"") && html.contains("value=\"PROJECT\">项目级"));
        assertTrue(html.contains("selectedSplitLevel() === \"PROJECT\""));
        assertTrue(html.contains("COSMIC 初拆") && html.contains("安全建议采纳"));
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
    }
}
