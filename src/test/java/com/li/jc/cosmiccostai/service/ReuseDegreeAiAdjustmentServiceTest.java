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
        assertTrue(prompt.contains("三项完全相同但整个功能过程并不重复"));
        assertTrue(prompt.contains("当前行自动设为复用/0.33"));
        assertTrue(prompt.contains("导入或上传若是同模块新增数据功能的配套能力"));
        assertTrue(prompt.contains("若同模块不存在新增功能则可作为新增基准"));
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
    void importAndUploadSupportingNewDataAreReuse() {
        ReuseDegreeAiAdjustmentService service = service(null, null, null);
        List<FunctionSplitRow> rows = Arrays.asList(
                moduleRow("客户资料", "新增客户资料", "新增"),
                moduleRow("客户资料", "导入客户资料", "新增"),
                moduleRow("客户资料", "上传客户资料", "新增"));

        List<ReuseDegreeAdjustmentDecision> decisions = service.applyModuleReuseDefaults(rows);

        assertEquals(2, decisions.size());
        assertEquals("新增", rows.get(0).getReuseDegree());
        assertEquals("复用", rows.get(1).getReuseDegree());
        assertEquals("复用", rows.get(2).getReuseDegree());
        assertEquals(0.33D, rows.get(1).getCfp(), 0.0001D);
        assertEquals(0.33D, rows.get(2).getCfp(), 0.0001D);
        assertEquals(Collections.singletonList(1), decisions.get(0).getReferenceRowIndexes());
        assertTrue(decisions.get(0).getReason().contains("配套能力"));
    }

    @Test
    void uploadWithoutNewDataCanRemainNewAnchor() {
        ReuseDegreeAiAdjustmentService service = service(null, null, null);
        List<FunctionSplitRow> rows = Arrays.asList(
                moduleRow("客户资料", "上传客户资料", "复用"),
                moduleRow("客户资料", "修改客户资料", "新增"));

        List<ReuseDegreeAdjustmentDecision> decisions = service.applyModuleReuseDefaults(rows);

        assertEquals(2, decisions.size());
        assertEquals("新增", rows.get(0).getReuseDegree());
        assertEquals("复用", rows.get(1).getReuseDegree());
        assertTrue(decisions.get(0).getReason().contains("可作为独立新增基准"));
    }

    @Test
    void workflowWordInsideManagedObjectDoesNotBlockWholeProcessReuseAdjustment() {
        ReuseDegreeAiAdjustmentService service = service(null, null, null);
        List<FunctionSplitRow> rows = Arrays.asList(
                moduleMovementRow("盘点单派发规则管理模块", "新增盘点单派发规则", "E", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "新增盘点单派发规则", "W", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "新增盘点单派发规则", "X", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "查询盘点单派发规则", "E", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "查询盘点单派发规则", "R", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "查询盘点单派发规则", "X", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "修改盘点单派发规则", "E", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "修改盘点单派发规则", "R", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "修改盘点单派发规则", "W", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "修改盘点单派发规则", "X", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "删除盘点单派发规则", "E", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "删除盘点单派发规则", "W", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "删除盘点单派发规则", "X", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "导出盘点单派发规则", "E", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "导出盘点单派发规则", "R", "新增"),
                moduleMovementRow("盘点单派发规则管理模块", "导出盘点单派发规则", "X", "新增"));

        List<ReuseDegreeAdjustmentDecision> decisions = service.applyModuleReuseDefaults(rows);

        assertEquals(10, decisions.size());
        for (int index = 0; index < 6; index++) {
            assertEquals("新增", rows.get(index).getReuseDegree());
            assertEquals(1D, rows.get(index).getCfp(), 0.0001D);
        }
        for (int index = 6; index < rows.size(); index++) {
            assertEquals("复用", rows.get(index).getReuseDegree());
            assertEquals(0.33D, rows.get(index).getCfp(), 0.0001D);
        }
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

    @Test
    void crossFunctionTwoMatchingAuditDimensionsAreAutomaticallyReuse() {
        ReuseDegreeAiAdjustmentService service = service(null, null, null);
        FunctionSplitRow previous = row("查询客户资料", "读取客户资料", "R",
                "客户资料", "客户编号、客户名称", "新增");
        FunctionSplitRow current = row("展示客户资料", "获取客户资料", "R",
                "客户资料", "客户名称、客户编号", "新增");
        previous.setLevel3Module("客户资料管理");
        current.setLevel3Module("客户资料管理");

        List<FunctionSplitRow> rows = Arrays.asList(previous, current);
        List<ReuseDegreeAdjustmentDecision> decisions = service.applyModuleReuseDefaults(rows);

        assertEquals(1, decisions.size());
        assertEquals("新增", rows.get(0).getReuseDegree());
        assertEquals("复用", rows.get(1).getReuseDegree());
        assertEquals(0.33D, rows.get(1).getCfp(), 0.0001D);
        assertEquals("CROSS_FUNCTION_TWO_DIMENSIONS", decisions.get(0).getDuplicateScope());
        assertEquals(Collections.singletonList(1), decisions.get(0).getReferenceRowIndexes());
        assertTrue(decisions.get(0).getMatchedDimensions().contains("movementType"));
        assertTrue(decisions.get(0).getMatchedDimensions().contains("dataAttributes"));
    }

    @Test
    void crossFunctionThreeMatchingDimensionsRemainFunctionDuplicateRisk() {
        ReuseDegreeAiAdjustmentService service = service(null, null, null);
        FunctionSplitRow previous = row("查询客户资料", "读取客户资料", "R",
                "客户资料", "客户编号、客户名称", "新增");
        FunctionSplitRow current = row("展示客户资料", "读取客户资料", "R",
                "客户资料", "客户名称、客户编号", "新增");
        previous.setLevel3Module("客户资料管理");
        current.setLevel3Module("客户资料管理");

        List<FunctionSplitRow> rows = Arrays.asList(previous, current);
        List<ReuseDegreeAdjustmentDecision> decisions = service.applyModuleReuseDefaults(rows);

        assertTrue(decisions.isEmpty());
        assertEquals("新增", rows.get(1).getReuseDegree());
        assertEquals(1D, rows.get(1).getCfp(), 0.0001D);
    }

    @Test
    void exactSharedMovementInOtherwiseDifferentLaterFunctionIsReuse() {
        ReuseDegreeAiAdjustmentService service = service(null, null, null);
        FunctionSplitRow firstEntry = row("查询客户资料", "接收客户查询条件", "E",
                "客户查询条件", "查询关键字", "新增");
        FunctionSplitRow firstRead = row("查询客户资料", "读取客户资料", "R",
                "客户资料", "客户编号、客户名称", "新增");
        FunctionSplitRow secondEntry = row("展示客户资料", "接收客户展示请求", "E",
                "客户展示请求", "展示场景", "新增");
        FunctionSplitRow secondRead = row("展示客户资料", "读取客户资料", "R",
                "客户资料", "客户名称、客户编号", "新增");
        FunctionSplitRow secondExit = row("展示客户资料", "返回客户展示结果", "X",
                "客户展示结果", "客户编号、展示状态", "新增");
        for (FunctionSplitRow row : Arrays.asList(firstEntry, firstRead, secondEntry, secondRead, secondExit)) {
            row.setLevel3Module("客户资料管理");
        }

        List<FunctionSplitRow> rows = Arrays.asList(
                firstEntry, firstRead, secondEntry, secondRead, secondExit);
        List<ReuseDegreeAdjustmentDecision> decisions = service.applyModuleReuseDefaults(rows);

        assertEquals(1, decisions.size());
        assertEquals("复用", secondRead.getReuseDegree());
        assertEquals(0.33D, secondRead.getCfp(), 0.0001D);
        assertEquals("CROSS_FUNCTION_SHARED_MOVEMENT", decisions.get(0).getDuplicateScope());
        assertEquals(Collections.singletonList(2), decisions.get(0).getReferenceRowIndexes());
        assertEquals(3, decisions.get(0).getMatchedDimensions().size());
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
