package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSplitAiServiceTest {

    @Test
    void keepsOneFunctionProcessInSameChunkWhenPromptBudgetIsSmall() {
        DeepSplitAiService service = service(6000, 3000);
        List<FunctionSplitRow> rows = Arrays.asList(
                row("客户审核", repeat("审核申请信息", 260)),
                row("客户审核", repeat("保存审核结果", 260)),
                row("订单查询", repeat("读取订单信息", 260)),
                row("报表导出", repeat("生成报表文件", 260)));

        List<DeepSplitAiService.DeepSplitChunk> chunks = service.buildChunks(rows);

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(0).getRowNumbers().contains(1));
        assertTrue(chunks.get(0).getRowNumbers().contains(2));
    }

    @Test
    void retrievesRequirementEvidenceRelatedToCurrentFunctionProcess() {
        DeepSplitAiService service = service(8000, 3000);
        List<DeepSplitAiService.EvidenceBlock> blocks = new ArrayList<DeepSplitAiService.EvidenceBlock>();
        blocks.add(new DeepSplitAiService.EvidenceBlock("REQ-B0001", "系统维护服务器网络参数。", 0));
        blocks.add(new DeepSplitAiService.EvidenceBlock("REQ-B0002", "审核人员读取客户申请并提交客户审核结论。", 1));
        List<FunctionSplitRow> rows = Arrays.asList(row("客户审核", "读取客户申请并保存审核结论"));

        List<DeepSplitAiService.EvidenceBlock> selected = service.selectEvidenceBlocks(blocks, rows, Arrays.asList(1));

        assertEquals(1, selected.size());
        assertEquals("REQ-B0002", selected.get(0).getId());
    }

    private DeepSplitAiService service(int promptChars, int evidenceChars) {
        return new DeepSplitAiService(
                new FileStorageService("target/deep-split-test-storage"),
                new SyncTaskExecutor(),
                "https://example.invalid/v1",
                "test-key",
                "test-model",
                30,
                0,
                promptChars,
                evidenceChars,
                10000,
                10);
    }

    private FunctionSplitRow row(String process, String description) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setProjectName("测试项目");
        row.setLevel1Module("客户管理");
        row.setLevel2Module("客户管理");
        row.setLevel3Module("客户审核");
        row.setFunctionProcess(process);
        row.setFunctionDescription(description);
        row.setSubProcessDescription(description);
        row.setMovementType("E");
        row.setDataGroup("客户申请");
        row.setDataAttributes("申请编号、审核状态");
        row.setReuseDegree("新增");
        row.setCfp(1);
        return row;
    }

    private String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
