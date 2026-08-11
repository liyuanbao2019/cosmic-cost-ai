package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.AiModelProfile;
import com.li.jc.cosmiccostai.dto.ReuseDegreeAdjustmentDecision;
import com.li.jc.cosmiccostai.dto.ReuseDegreeAdjustmentResponse;
import com.li.jc.cosmiccostai.dto.ReviewResponse;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReuseDegreeAiAdjustmentServiceTest {

    @Test
    void appliesOnlyEvidenceBackedMovementReuse() {
        ReuseDegreeAiAdjustmentService service = service(null, null, null);
        List<FunctionSplitRow> rows = Arrays.asList(
                row("功能A", "提交查询条件", "E", "查询条件", "地市、类型", "新增"),
                row("功能B", "发起工单查询", "E", "工单查询条件", "地市、类型", "新增"));

        ReuseDegreeAdjustmentDecision accepted = decision(1, "复用", "MOVEMENT", 0.96D,
                Collections.singletonList(2), Arrays.asList("movementType", "dataAttributes"));
        ReuseDegreeAdjustmentDecision rejected = decision(2, "复用", "MOVEMENT", 0.98D,
                Collections.<Integer>emptyList(), Arrays.asList("movementType", "dataAttributes"));

        ReuseDegreeAiAdjustmentService.ApplySummary summary = service.applyDecisions(
                rows, Arrays.asList(accepted, rejected));

        assertEquals("复用", rows.get(0).getReuseDegree());
        assertEquals(0.33D, rows.get(0).getCfp(), 0.0001D);
        assertTrue(accepted.isApplied());
        assertEquals("新增", rows.get(1).getReuseDegree());
        assertFalse(rejected.isApplied());
        assertTrue(rejected.getApplyMessage().contains("有效行"));
        assertEquals(1, summary.getApplied());
        assertEquals(1, summary.getSkipped());
    }

    @Test
    void legacyRequiresCompleteFunctionCoverage() {
        ReuseDegreeAiAdjustmentService service = service(null, null, null);
        List<FunctionSplitRow> rows = Arrays.asList(
                row("功能A", "发起查询", "E", "查询条件", "编号", "新增"),
                row("功能A", "返回查询结果", "X", "查询结果", "编号、状态", "新增"),
                row("历史功能", "发起查询", "E", "查询条件", "编号", "新增"));
        ReuseDegreeAdjustmentDecision onlyOne = decision(1, "利旧", "FUNCTION", 0.99D,
                Collections.singletonList(3), Arrays.asList(
                        "subProcessDescription", "movementType", "dataAttributes"));

        ReuseDegreeAiAdjustmentService.ApplySummary summary = service.applyDecisions(
                rows, Collections.singletonList(onlyOne));

        assertEquals("新增", rows.get(0).getReuseDegree());
        assertFalse(onlyOne.isApplied());
        assertTrue(onlyOne.getApplyMessage().contains("全部子过程行"));
        assertEquals(0, summary.getApplied());
    }

    @Test
    void canRestoreRealBoundaryDifferenceToNew() {
        ReuseDegreeAiAdjustmentService service = service(null, null, null);
        List<FunctionSplitRow> rows = Arrays.asList(
                row("跨域查询", "发起跨域查询", "E", "跨域查询条件", "地市、账号", "复用"),
                row("普通查询", "发起查询", "E", "查询条件", "地市、账号", "新增"));
        ReuseDegreeAdjustmentDecision decision = decision(1, "新增", "MOVEMENT", 0.94D,
                Collections.<Integer>emptyList(), Collections.<String>emptyList());
        decision.setBoundaryDifference("功能用户不同，数据从省级平台跨越外部软件边界进入本系统。");

        service.applyDecisions(rows, Collections.singletonList(decision));

        assertTrue(decision.isApplied());
        assertEquals("新增", rows.get(0).getReuseDegree());
        assertEquals(1D, rows.get(0).getCfp(), 0.0001D);
    }

    @Test
    void adjustmentUsesDedicatedTaskAndPersistsAdjustedRows() {
        AiModelConfigService ai = mock(AiModelConfigService.class);
        SplitRuleConfigService rules = mock(SplitRuleConfigService.class);
        ReviewService review = mock(ReviewService.class);
        when(rules.getEffectiveRulesPrompt()).thenReturn("FULL-RULE-V2");
        when(rules.getRuleVersion()).thenReturn("R-test");
        when(ai.isDeepSeekActive()).thenReturn(false);
        when(ai.generateForTask(any(String.class), eq(AiPromptTask.REUSE_ADJUSTMENT), eq(false)))
                .thenReturn("{\"decisions\":[]}");
        AiModelProfile profile = new AiModelProfile();
        profile.setModel("deepseek-reasoner");
        when(ai.getActiveProfile()).thenReturn(profile);
        ReviewResponse saved = new ReviewResponse();
        List<FunctionSplitRow> rows = Arrays.asList(
                row("查询", "发起查询", "E", "查询条件", "编号", "新增"),
                row("查询", "返回结果", "X", "查询结果", "编号、状态", "新增"));
        saved.setSplitRows(rows);
        when(review.saveReuseAdjustedReview(eq("p-1"), any())).thenReturn(saved);

        ReuseDegreeAdjustmentResponse response = service(ai, rules, review).adjust("p-1", rows);

        assertEquals(2, response.getTotalRows());
        assertEquals(2D, response.getCfpBefore(), 0.0001D);
        assertEquals(2D, response.getCfpAfter(), 0.0001D);
        assertEquals("deepseek-reasoner", response.getModel());
        verify(ai).generateForTask(any(String.class), eq(AiPromptTask.REUSE_ADJUSTMENT), eq(false));
        verify(review).saveReuseAdjustedReview(eq("p-1"), any());
    }

    @Test
    void promptOptimizesAuditAccuracyInsteadOfCfpDirection() {
        AiModelConfigService ai = mock(AiModelConfigService.class);
        SplitRuleConfigService rules = mock(SplitRuleConfigService.class);
        when(rules.getEffectiveRulesPrompt()).thenReturn("FULL-RULE-V2-UNIQUE");
        when(rules.getRuleVersion()).thenReturn("R-test");
        when(ai.isDeepSeekActive()).thenReturn(false);
        ReuseDegreeAiAdjustmentService service = service(ai, rules, mock(ReviewService.class));

        String prompt = service.buildPrompt(Collections.singletonList(
                row("查询", "发起查询", "E", "条件", "编号", "新增")));

        assertTrue(prompt.contains("降低后续核减率"));
        assertTrue(prompt.contains("不追求 CFP 多，也不追求 CFP 少"));
        assertTrue(prompt.contains("不得为了消除初审提示而改值"));
        assertTrue(prompt.contains("只要存在新增、查询、导入任意一种独立功能过程"));
        assertTrue(prompt.contains("同模块修改、删除、导出、下载功能过程的全部子过程设为复用"));
        assertTrue(prompt.contains("不要求增删改查四类齐全"));
        assertTrue(prompt.contains("该规则由服务端统一落地"));
        assertTrue(prompt.contains("完整拆分表，共 1 行"));
        assertTrue(prompt.contains("FULL-RULE-V2-UNIQUE"));
    }

    @Test
    void singleQueryAnchorUsesNewForQueryAndReuseForModifyDeleteExportDownload() {
        AiModelConfigService ai = mock(AiModelConfigService.class);
        SplitRuleConfigService rules = mock(SplitRuleConfigService.class);
        ReviewService review = mock(ReviewService.class);
        when(rules.getEffectiveRulesPrompt()).thenReturn("FULL-RULE-V2");
        when(rules.getRuleVersion()).thenReturn("R-crud");
        when(ai.isDeepSeekActive()).thenReturn(false);
        when(ai.generateForTask(any(String.class), eq(AiPromptTask.REUSE_ADJUSTMENT), eq(false)))
                .thenReturn("{\"decisions\":[]}");
        AiModelProfile profile = new AiModelProfile();
        profile.setModel("deepseek-reasoner");
        when(ai.getActiveProfile()).thenReturn(profile);

        List<FunctionSplitRow> rows = Arrays.asList(
                moduleMovementRow("客户资料", "查询客户资料", "E", "复用"),
                moduleMovementRow("客户资料", "查询客户资料", "X", "复用"),
                moduleMovementRow("客户资料", "删除客户资料", "E", "新增"),
                moduleMovementRow("客户资料", "删除客户资料", "W", "新增"),
                moduleMovementRow("客户资料", "修改客户资料", "W", "新增"),
                moduleMovementRow("客户资料", "导出客户资料", "X", "新增"),
                moduleMovementRow("客户资料", "下载客户资料", "X", "新增"));
        when(review.saveReuseAdjustedReview(eq("p-anchor"), any())).thenAnswer(invocation -> {
            ReviewResponse saved = new ReviewResponse();
            @SuppressWarnings("unchecked")
            List<FunctionSplitRow> savedRows = (List<FunctionSplitRow>) invocation.getArgument(1);
            saved.setSplitRows(savedRows);
            return saved;
        });

        ReuseDegreeAdjustmentResponse response = service(ai, rules, review).adjust("p-anchor", rows);

        assertEquals(0, response.getModelDecisionCount());
        assertEquals(7, response.getAdjustedRows());
        assertEquals(7, response.getRuleAdjustedRows());
        assertEquals(7, response.getDecisions().size());
        assertEquals("新增", response.getRows().get(0).getReuseDegree());
        assertEquals("新增", response.getRows().get(1).getReuseDegree());
        assertEquals("复用", response.getRows().get(2).getReuseDegree());
        assertEquals("复用", response.getRows().get(3).getReuseDegree());
        assertEquals("复用", response.getRows().get(4).getReuseDegree());
        assertEquals("复用", response.getRows().get(5).getReuseDegree());
        assertEquals("复用", response.getRows().get(6).getReuseDegree());
        assertEquals(1D, response.getRows().get(0).getCfp(), 0.0001D);
        assertEquals(0.33D, response.getRows().get(2).getCfp(), 0.0001D);
        assertEquals(3.65D, response.getCfpAfter(), 0.0001D);
        for (ReuseDegreeAdjustmentDecision decision : response.getDecisions()) {
            assertEquals("MODULE_ANCHOR_DEFAULT", decision.getDuplicateScope());
            assertTrue(decision.isApplied());
            assertTrue(decision.getApplyMessage().contains("同模块基准功能默认规则"));
        }
    }

    @Test
    void importAloneCanAnchorModifyAndDeleteReuse() {
        ReuseDegreeAiAdjustmentService service = service(null, null, null);
        List<FunctionSplitRow> rows = Arrays.asList(
                moduleRow("客户资料", "导入客户资料", "复用"),
                moduleRow("客户资料", "修改客户资料", "新增"),
                moduleRow("客户资料", "删除客户资料", "新增"));

        List<ReuseDegreeAdjustmentDecision> decisions = service.applyModuleReuseDefaults(rows);

        assertEquals(3, decisions.size());
        assertEquals("新增", rows.get(0).getReuseDegree());
        assertEquals("复用", rows.get(1).getReuseDegree());
        assertEquals("复用", rows.get(2).getReuseDegree());
    }

    @Test
    void moduleDefaultRequiresAnAnchorAndDoesNotCombineDifferentModules() {
        ReuseDegreeAiAdjustmentService service = service(null, null, null);
        List<FunctionSplitRow> rows = Arrays.asList(
                moduleRow("客户资料", "修改客户资料", "新增"),
                moduleRow("客户资料", "导出客户资料", "新增"),
                moduleRow("客户资料", "删除客户资料", "新增"),
                moduleRow("角色资料", "查询角色资料", "新增"));

        List<ReuseDegreeAdjustmentDecision> decisions = service.applyModuleReuseDefaults(rows);

        assertTrue(decisions.isEmpty());
        assertEquals("新增", rows.get(0).getReuseDegree());
        assertEquals("新增", rows.get(1).getReuseDegree());
        assertEquals("新增", rows.get(2).getReuseDegree());
    }

    private ReuseDegreeAiAdjustmentService service(AiModelConfigService ai,
                                                    SplitRuleConfigService rules,
                                                    ReviewService review) {
        return new ReuseDegreeAiAdjustmentService(ai, rules, review);
    }

    private ReuseDegreeAdjustmentDecision decision(int rowIndex,
                                                    String target,
                                                    String scope,
                                                    double confidence,
                                                    List<Integer> references,
                                                    List<String> dimensions) {
        ReuseDegreeAdjustmentDecision decision = new ReuseDegreeAdjustmentDecision();
        decision.setRowIndex(rowIndex);
        decision.setSuggestedReuseDegree(target);
        decision.setDuplicateScope(scope);
        decision.setReferenceRowIndexes(references);
        decision.setMatchedDimensions(dimensions);
        decision.setReason("基于当前行、参考行及需求证据完成对照。");
        decision.setEvidenceSufficient(true);
        decision.setConfidence(confidence);
        return decision;
    }

    private FunctionSplitRow row(String process,
                                 String description,
                                 String movement,
                                 String group,
                                 String attributes,
                                 String reuse) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setLevel1Module("模块");
        row.setLevel2Module("子模块");
        row.setLevel3Module(process);
        row.setFunctionalUser("发起者：用户\n接收者：本系统");
        row.setTriggerEvent(process);
        row.setFunctionProcess(process);
        row.setSubProcessDescription(description);
        row.setMovementType(movement);
        row.setDataGroup(group);
        row.setDataAttributes(attributes);
        row.setReuseDegree(reuse);
        row.setCfp("复用".equals(reuse) ? 0.33D : ("利旧".equals(reuse) ? 0D : 1D));
        row.setSourceReferences(new ArrayList<String>());
        return row;
    }

    private FunctionSplitRow moduleRow(String module, String process, String reuse) {
        return moduleMovementRow(module, process, "E", reuse);
    }

    private FunctionSplitRow moduleMovementRow(String module, String process,
                                                String movement, String reuse) {
        FunctionSplitRow row = row(process, process + "子过程", movement,
                process + "数据组", "业务编号、业务名称、业务状态", reuse);
        row.setLevel1Module("业务管理");
        row.setLevel2Module("资料管理");
        row.setLevel3Module(module);
        return row;
    }
}
