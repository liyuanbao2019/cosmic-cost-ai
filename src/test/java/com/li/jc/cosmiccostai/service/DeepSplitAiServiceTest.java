package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.DeepSplitSuggestion;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void deepReviewPromptIncludesBusinessActionMovementRules() {
        DeepSplitAiService service = service(8000, 3000);
        DeepSplitAiService.DeepSplitJobState state = new DeepSplitAiService.DeepSplitJobState();
        state.setOriginalRows(Collections.singletonList(row("用户导出", "生成导出文件")));
        DeepSplitAiService.DeepSplitChunk chunk = new DeepSplitAiService.DeepSplitChunk();
        chunk.setRowNumbers(Collections.singletonList(1));

        String prompt = service.buildPrompt(state, chunk, Collections.<DeepSplitAiService.EvidenceBlock>emptyList());

        assertTrue(prompt.contains("REMOVE_UNSUPPORTED_MOVEMENT"));
        assertTrue(prompt.contains("ADJUST_MOVEMENT_STRUCTURE"));
        assertTrue(prompt.contains("ADJUST_REUSE_CLASSIFICATION"));
        assertTrue(prompt.contains("用户查询 E-R-X；新增/删除 E-W-X"));
        assertTrue(prompt.contains("文件写入服务器并保留为 E-R-W-X"));
        assertTrue(prompt.contains("也可合并为 E-W-W"));
        assertTrue(prompt.contains("被调用方：查询接口 E-R-X，接收同步数据 E-W-X"));
        assertTrue(prompt.contains("E 已携带查询所需参数并直接通过 X 发出请求"));
        assertTrue(prompt.contains("不得仅因功能名含“查询”补 R"));
        assertTrue(prompt.contains("可形成 E-X-E-W-X 或多组 X-E"));
        assertTrue(prompt.contains("ReturnCode/ReturnMessage 是第二个功能过程的 X"));
        assertTrue(prompt.contains("请求请求"));
        assertTrue(prompt.contains("用户/UI 主动触发写“发起XXX查询"));
        assertTrue(prompt.contains("外部平台上报/回调才写"));
        assertTrue(prompt.contains("新疆项目不因“读取相关数据”“保存处理结果”“返回处理结果”等子过程表述本身判定风险或核减"));
        assertTrue(prompt.contains("每个功能过程至少包含 2 行"));
        assertTrue(prompt.contains("不设置最多 4 行限制"));
        assertFalse(prompt.contains("2—4 行"));
        assertFalse(prompt.contains("至少 6 个"));
        assertTrue(prompt.contains("正文中的“六级地址、故障原因、ret、P01、U13”"));
        assertTrue(prompt.contains("无载荷定时/时钟触发 E 填“无业务数据”"));
        assertTrue(prompt.contains("禁止“字段名（待确认）”混写"));
        assertTrue(prompt.contains("翻页、勾选/取消勾选、图层切换"));
        assertTrue(prompt.contains("复用判定三要素"));
        assertTrue(prompt.contains("新增=1、复用=0.33、利旧=0"));
        assertTrue(prompt.contains("末行必须是 W 或 X"));
        assertTrue(prompt.contains("必须做全表语义查重"));
        assertTrue(prompt.contains("历史评审批注优先于通用模板"));
        assertTrue(prompt.contains("查询与展示同一对象通常合并"));
        assertTrue(prompt.contains("【全表语义查重候选】"));
    }

    @Test
    void deepSeekDeepReviewUsesDedicatedObjectContractAndSingleRuleSource() {
        DeepSplitAiService service = service(8000, 3000, "deepseek-v4-pro");
        DeepSplitAiService.DeepSplitJobState state = new DeepSplitAiService.DeepSplitJobState();
        state.setOriginalRows(Collections.singletonList(row("用户导出", "生成导出文件")));
        DeepSplitAiService.DeepSplitChunk chunk = new DeepSplitAiService.DeepSplitChunk();
        chunk.setRowNumbers(Collections.singletonList(1));

        String prompt = service.buildPrompt(
                state, chunk, Collections.<DeepSplitAiService.EvidenceBlock>emptyList());
        String ruleTitle = "COSMIC 软件造价拆分基础规则 v2.0（设计院评审沉淀版）";

        assertTrue(prompt.contains("DeepSeek 专项执行协议"));
        assertTrue(prompt.contains("顶层为 suggestions 数组"));
        assertTrue(prompt.contains("{\"suggestions\":[]}"));
        assertTrue(prompt.indexOf(ruleTitle) >= 0);
        assertTrue(prompt.indexOf(ruleTitle) == prompt.lastIndexOf(ruleTitle));
        assertFalse(prompt.contains("输出严格 JSON 数组"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsDeepSeekObjectWrapperForDeepReview() throws Exception {
        DeepSplitAiService service = service(8000, 3000, "deepseek-v4-pro");
        Method method = DeepSplitAiService.class.getDeclaredMethod("parseSuggestions", String.class);
        method.setAccessible(true);

        List<DeepSplitSuggestion> suggestions = (List<DeepSplitSuggestion>) method.invoke(service,
                "{\"suggestions\":[{\"operation\":\"ADJUST_REUSE_CLASSIFICATION\",\"title\":\"调整复用\"}]}");

        assertEquals(1, suggestions.size());
        assertEquals("ADJUST_REUSE_CLASSIFICATION", suggestions.get(0).getOperation());
    }

    @Test
    void acceptsSuggestionThatRemovesUnsupportedMovement() {
        DeepSplitAiService service = service(8000, 3000);
        DeepSplitAiService.DeepSplitJobState state = new DeepSplitAiService.DeepSplitJobState();
        state.setJobId("job-1");
        state.setOriginalRows(Arrays.asList(
                row("用户新增", "接收新增请求", "E"),
                row("用户新增", "读取用户记录", "R"),
                row("用户新增", "保存用户记录", "W"),
                row("用户新增", "输出新增结果", "X")));
        DeepSplitAiService.DeepSplitChunk chunk = new DeepSplitAiService.DeepSplitChunk();
        chunk.setId("chunk-1");
        chunk.setRowNumbers(Arrays.asList(1, 2, 3, 4));

        DeepSplitSuggestion suggestion = new DeepSplitSuggestion();
        suggestion.setOperation("REMOVE_UNSUPPORTED_MOVEMENT");
        suggestion.setReason("新增功能没有读取持久化数据的依据");
        suggestion.setEvidence("原拆分行仅体现新增请求和写入结果");
        suggestion.setSourceReferences(Arrays.asList("EXCEL-ROW-1", "EXCEL-ROW-2", "EXCEL-ROW-3", "EXCEL-ROW-4"));
        suggestion.setConfidence(0.95);
        suggestion.setTargetRowNumbers(Arrays.asList(1, 2, 3, 4));
        suggestion.setReplacementRows(Arrays.asList(
                row("用户新增", "接收新增请求", "E"),
                row("用户新增", "保存用户记录", "W"),
                row("用户新增", "输出新增结果", "X")));

        List<DeepSplitSuggestion> normalized = service.normalizeSuggestions(
                state,
                chunk,
                Collections.<DeepSplitAiService.EvidenceBlock>emptyList(),
                Collections.singletonList(suggestion));

        assertEquals(1, normalized.size());
        assertEquals("REMOVE_UNSUPPORTED_MOVEMENT", normalized.get(0).getOperation());
        assertEquals(3, normalized.get(0).getReplacementRows().size());
        assertTrue(normalized.get(0).getTitle().contains("删除无依据数据移动"));
    }

    @Test
    void acceptsSameSizeMovementStructureAdjustment() {
        DeepSplitAiService service = service(8000, 3000);
        DeepSplitAiService.DeepSplitJobState state = new DeepSplitAiService.DeepSplitJobState();
        state.setJobId("job-2");
        state.setOriginalRows(Arrays.asList(
                row("用户删除", "接收删除请求", "E"),
                row("用户删除", "读取用户记录", "R"),
                row("用户删除", "删除用户记录", "W")));
        DeepSplitAiService.DeepSplitChunk chunk = new DeepSplitAiService.DeepSplitChunk();
        chunk.setId("chunk-2");
        chunk.setRowNumbers(Arrays.asList(1, 2, 3));

        DeepSplitSuggestion suggestion = new DeepSplitSuggestion();
        suggestion.setOperation("ADJUST_MOVEMENT_STRUCTURE");
        suggestion.setReason("删除应输出处理结果，不应以无依据读取替代结果输出");
        suggestion.setEvidence("现有行包含删除请求和写入，但缺少输出结果");
        suggestion.setSourceReferences(Arrays.asList("EXCEL-ROW-1", "EXCEL-ROW-2", "EXCEL-ROW-3"));
        suggestion.setConfidence(0.95);
        suggestion.setTargetRowNumbers(Arrays.asList(1, 2, 3));
        suggestion.setReplacementRows(Arrays.asList(
                row("用户删除", "接收删除请求", "E"),
                row("用户删除", "删除用户记录", "W"),
                row("用户删除", "输出删除结果", "X")));

        List<DeepSplitSuggestion> normalized = service.normalizeSuggestions(
                state,
                chunk,
                Collections.<DeepSplitAiService.EvidenceBlock>emptyList(),
                Collections.singletonList(suggestion));

        assertEquals(1, normalized.size());
        assertEquals("ADJUST_MOVEMENT_STRUCTURE", normalized.get(0).getOperation());
        assertEquals(3, normalized.get(0).getReplacementRows().size());
        assertTrue(normalized.get(0).getTitle().contains("调整数据移动结构"));
    }

    @Test
    void rejectsDeepSuggestionThatStillViolatesDeterministicStructureRules() {
        DeepSplitAiService service = service(8000, 3000);
        DeepSplitAiService.DeepSplitJobState state = new DeepSplitAiService.DeepSplitJobState();
        state.setJobId("job-invalid-structure");
        state.setOriginalRows(Arrays.asList(
                row("修改用户", "提交修改请求", "E"),
                row("修改用户", "读取用户记录", "R"),
                row("修改用户", "保存用户记录", "W"),
                row("修改用户", "输出修改结果", "X")));
        DeepSplitAiService.DeepSplitChunk chunk = new DeepSplitAiService.DeepSplitChunk();
        chunk.setId("chunk-invalid-structure");
        chunk.setRowNumbers(Arrays.asList(1, 2, 3, 4));

        DeepSplitSuggestion suggestion = new DeepSplitSuggestion();
        suggestion.setOperation("ADJUST_MOVEMENT_STRUCTURE");
        suggestion.setReason("测试错误顺序不能通过深审采纳门禁");
        suggestion.setEvidence("EXCEL 行证据");
        suggestion.setSourceReferences(Arrays.asList("EXCEL-ROW-1", "EXCEL-ROW-2", "EXCEL-ROW-3", "EXCEL-ROW-4"));
        suggestion.setConfidence(0.95);
        suggestion.setTargetRowNumbers(Arrays.asList(1, 2, 3, 4));
        suggestion.setReplacementRows(Arrays.asList(
                row("修改用户", "提交修改请求", "E"),
                row("修改用户", "过早输出修改结果", "X"),
                row("修改用户", "读取用户记录", "R"),
                row("修改用户", "保存用户记录", "W")));

        List<DeepSplitSuggestion> normalized = service.normalizeSuggestions(
                state,
                chunk,
                Collections.<DeepSplitAiService.EvidenceBlock>emptyList(),
                Collections.singletonList(suggestion));

        assertTrue(normalized.isEmpty());
    }

    @Test
    void suppliesCrossChunkDuplicateCandidatesAndAcceptsReuseClassificationAdjustment() {
        DeepSplitAiService service = service(8000, 3000);
        FunctionSplitRow current = row("查询客户申请", "读取客户申请", "R");
        FunctionSplitRow duplicate = row("展示客户申请", "读取客户申请", "R");
        FunctionSplitRow unrelated = row("删除设备信息", "删除设备记录", "W");

        DeepSplitAiService.DeepSplitJobState state = new DeepSplitAiService.DeepSplitJobState();
        state.setJobId("job-duplicate");
        state.setOriginalRows(Arrays.asList(current, duplicate, unrelated));
        DeepSplitAiService.DeepSplitChunk chunk = new DeepSplitAiService.DeepSplitChunk();
        chunk.setId("chunk-duplicate");
        chunk.setRowNumbers(Collections.singletonList(1));

        String prompt = service.buildPrompt(state, chunk, Collections.<DeepSplitAiService.EvidenceBlock>emptyList());
        assertTrue(prompt.contains("【全表语义查重候选】"));
        assertTrue(prompt.contains("EXCEL-ROW-2"));

        FunctionSplitRow replacement = row("查询客户申请", "读取客户申请", "R");
        replacement.setReuseDegree("利旧");
        replacement.setCfp(0);
        DeepSplitSuggestion suggestion = new DeepSplitSuggestion();
        suggestion.setOperation("ADJUST_REUSE_CLASSIFICATION");
        suggestion.setReason("与全表候选功能重复");
        suggestion.setEvidence("第2行三要素完全相同");
        suggestion.setSourceReferences(Collections.singletonList("EXCEL-ROW-2"));
        suggestion.setConfidence(0.95);
        suggestion.setTargetRowNumbers(Collections.singletonList(1));
        suggestion.setReplacementRows(Collections.singletonList(replacement));

        List<DeepSplitSuggestion> normalized = service.normalizeSuggestions(
                state, chunk, Collections.<DeepSplitAiService.EvidenceBlock>emptyList(),
                Collections.singletonList(suggestion));
        assertEquals(1, normalized.size());
        assertEquals("ADJUST_REUSE_CLASSIFICATION", normalized.get(0).getOperation());
    }

    private DeepSplitAiService service(int promptChars, int evidenceChars) {
        return service(promptChars, evidenceChars, "test-model");
    }

    private DeepSplitAiService service(int promptChars, int evidenceChars, String model) {
        return new DeepSplitAiService(
                new FileStorageService("target/deep-split-test-storage"),
                new SyncTaskExecutor(),
                "https://example.invalid/v1",
                "test-key",
                model,
                30,
                0,
                promptChars,
                evidenceChars,
                10000,
                10);
    }

    private FunctionSplitRow row(String process, String description) {
        return row(process, description, "E");
    }

    private FunctionSplitRow row(String process, String description, String movement) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setProjectName("测试项目");
        row.setLevel1Module("客户管理");
        row.setLevel2Module("客户管理");
        row.setLevel3Module("客户审核");
        row.setFunctionProcess(process);
        row.setFunctionDescription(description);
        row.setSubProcessDescription(description);
        row.setMovementType(movement);
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
