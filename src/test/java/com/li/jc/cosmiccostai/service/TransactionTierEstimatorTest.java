package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.ProcessTier;
import com.li.jc.cosmiccostai.dto.ReuseDegreeAdjustmentDecision;
import com.li.jc.cosmiccostai.dto.RuleValidationIssue;
import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.dto.StrictEstimate;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 事务三档法严口径预估与过程级复用度统一（规则 v2.8 第 3 节、第 11-2 条）。 */
class TransactionTierEstimatorTest {

    private static final String HUMAN = "发起者：装维人员\n接收者：运维系统";
    private static final String TIMER = "发起者：调度任务\n接收者：运维系统";
    private static final String EOMS = "发起者：集团 EOMS\n接收者：运维系统";

    @Test
    void newObjectFirstQueryAndCreateAreNewAndRelatedOperationsAreModify() {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        rows.addAll(process("设备台账", "设备信息查询", HUMAN, "装维人员触发设备信息查询", "新增", "ERX"));
        rows.addAll(process("设备台账", "设备信息新增", HUMAN, "装维人员触发设备信息新增", "新增", "EWX"));
        rows.addAll(process("设备台账", "设备信息修改", HUMAN, "装维人员触发设备信息修改", "新增", "ERWX"));
        rows.addAll(process("设备台账", "设备信息导出", HUMAN, "装维人员触发设备信息导出", "新增", "ERX"));

        StrictEstimate estimate = TransactionTierEstimator.estimate(rows);

        assertEquals("新", tier(estimate, "设备信息查询"));
        assertEquals("新", tier(estimate, "设备信息新增"));
        assertEquals("改", tier(estimate, "设备信息修改"));
        assertEquals("改", tier(estimate, "设备信息导出"));
        // 新 3 + 新 3 + 改（骨架 E、W、X 各 1/3）1 + 改 1 = 8；申报 13
        assertEquals(8D, estimate.getStrictCfp(), 0.01D);
        assertEquals(13D, estimate.getDeclaredCfp(), 0.01D);
        assertEquals(4, estimate.getTransactionCount());
        assertEquals(3.25D, estimate.getDeclaredPerTransaction(), 0.01D);
        assertEquals(0.5D, estimate.getNewShare(), 0.01D);
    }

    @Test
    void timerChainHeadIsTimerNewAndGranularityVariantIsZero() {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        rows.addAll(process("性能汇总", "小区15分钟性能汇总", TIMER, "每15分钟调度", "复用", "EW"));
        rows.addAll(process("性能汇总", "小区小时性能汇总", TIMER, "每小时调度", "复用", "EW"));
        rows.get(1).setReuseDegree("新增");
        rows.get(1).setCfp(1D);

        StrictEstimate estimate = TransactionTierEstimator.estimate(rows);

        assertEquals("定", tier(estimate, "小区15分钟性能汇总"));
        assertEquals("0", tier(estimate, "小区小时性能汇总"));
        assertEquals(1D / 3D + 1D, estimate.getStrictCfp(), 0.01D);
        assertEquals(1, estimate.getTimerNewCount());
        assertEquals(1, estimate.getZeroCount());
    }

    @Test
    void invalidProcessesAreZero() {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        rows.addAll(process("大屏", "告警卡片展示", HUMAN, "装维人员触发告警卡片展示", "新增", "ERX"));
        rows.addAll(process("推送", "资源文件推送", TIMER, "文件生成事件触发", "新增", "EX"));
        rows.addAll(process("数据链", "性能数据归档", TIMER, "每日 02:00 调度", "新增", "EW"));
        rows.addAll(process("调度", "获取分布式锁", TIMER, "每日 02:00 调度", "新增", "EW"));
        rows.addAll(process("台账", "站点台账查询", HUMAN, "装维人员触发站点台账查询", "利旧", "ERX"));

        StrictEstimate estimate = TransactionTierEstimator.estimate(rows);

        for (ProcessTier item : estimate.getProcesses()) {
            assertEquals("0", item.getTier(), item.getFunctionProcess() + "：" + item.getReason());
        }
        assertEquals(0D, estimate.getStrictCfp(), 1e-9);
        assertEquals(0, estimate.getTransactionCount());
    }

    @Test
    void optimizeTitleDeclaredReuseAndSecondInterfaceAreModify() {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        rows.addAll(process("工单时限优化", "工单时限查询", HUMAN, "装维人员触发工单时限查询", "新增", "ERX"));
        rows.addAll(process("故障工单", "故障工单查询", HUMAN, "装维人员触发故障工单查询", "复用", "ERX"));
        rows.addAll(process("派单接口", "接收EOMS故障派单", EOMS, "集团 EOMS 调用派单接口", "新增", "EWX"));
        rows.addAll(process("派单接口", "接收EOMS催办单", EOMS, "集团 EOMS 调用催办接口", "新增", "EWX"));

        StrictEstimate estimate = TransactionTierEstimator.estimate(rows);

        assertEquals("改", tier(estimate, "工单时限查询"));
        assertEquals("改", tier(estimate, "故障工单查询"));
        assertEquals("新", tier(estimate, "接收EOMS故障派单"));
        assertEquals("改", tier(estimate, "接收EOMS催办单"));
    }

    @Test
    void dispatcherRoleIsNotTreatedAsScheduler() {
        assertEquals("代维调度员", TransactionTierEstimator.initiator("发起者：代维调度员\n接收者：运维系统"));
        assertEquals("装维人员", TransactionTierEstimator.initiator("装维人员"));

        List<FunctionSplitRow> rows = process("工单", "工单派发", "发起者：代维调度员\n接收者：运维系统",
                "代维调度员触发工单派发", "新增", "EWX");
        assertEquals("新", tier(TransactionTierEstimator.estimate(rows), "工单派发"));
    }

    @Test
    void validationCarriesEstimateAndFlagsV31AndV34OnlyForEnoughTransactions() {
        List<FunctionSplitRow> small = process("设备台账", "设备信息修改", HUMAN, "装维人员触发设备信息修改", "新增", "ERWX");
        RuleValidationResponse smallResponse = new RuleValidationService().validate(small);
        assertNotNull(smallResponse.getStrictEstimate());
        assertFalse(hasRule(smallResponse, "V31"));

        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        for (int i = 1; i <= 10; i++) {
            rows.addAll(process("对象" + i, "对象" + i + "台账查询", HUMAN, "装维人员触发台账查询", "新增", "ERRX"));
        }
        RuleValidationResponse response = new RuleValidationService().validate(rows);

        assertEquals(10, response.getStrictEstimate().getTransactionCount());
        assertEquals(30D, response.getStrictEstimate().getStrictCfp(), 0.01D);
        assertTrue(hasRule(response, "V31"));
        assertTrue(hasRule(response, "V34"));
        for (RuleValidationIssue issue : response.getIssues()) {
            if ("V31".equals(issue.getRuleCode())) {
                assertEquals(0, issue.getRowIndex());
                assertEquals(10D, issue.getCfpImpact(), 0.01D);
            }
        }
    }

    @Test
    void reuseAdjustmentUnifiesMixedProcessByTier() {
        ReuseDegreeAiAdjustmentService service = new ReuseDegreeAiAdjustmentService(null, null, null, null, null);
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        rows.addAll(process("设备台账", "设备信息查询", HUMAN, "装维人员触发设备信息查询", "新增", "ERX"));
        rows.addAll(process("设备台账", "设备信息修改", HUMAN, "装维人员触发设备信息修改", "新增", "EWX"));
        rows.addAll(process("性能汇总", "小区性能汇总", TIMER, "每日 02:00 调度", "新增", "EWW"));
        reuse(rows.get(1), "复用");   // 新建档查询里混入复用 → 统一新增
        reuse(rows.get(3), "复用");   // 改造档修改里 E 复用、W/X 新增 → 统一复用
        reuse(rows.get(6), "复用");   // 定时首 E 复用：合法例外
        rows.get(8).setSubProcessDescription("记录汇总日志");
        reuse(rows.get(8), "复用");   // 日志 W 复用：合法例外

        List<ReuseDegreeAdjustmentDecision> decisions = service.unifyProcessReuse(rows);

        assertEquals("新增", rows.get(1).getReuseDegree());
        assertEquals(1D, rows.get(1).getCfp(), 1e-12);
        assertEquals("复用", rows.get(4).getReuseDegree());
        assertEquals("复用", rows.get(5).getReuseDegree());
        assertEquals(1D / 3D, rows.get(5).getCfp(), 1e-12);
        assertEquals("复用", rows.get(6).getReuseDegree());
        assertEquals("新增", rows.get(7).getReuseDegree());
        assertEquals("复用", rows.get(8).getReuseDegree());
        assertEquals(3, decisions.size());
        for (ReuseDegreeAdjustmentDecision decision : decisions) {
            assertEquals("PROCESS_LEVEL_UNIFY", decision.getDecisionType());
            assertEquals("V40", decision.getRuleId());
            assertTrue(decision.isApplied());
        }
        for (RuleValidationIssue issue : V28SplitChecks.check(rows)) {
            assertFalse("V40".equals(issue.getRuleCode()), "统一后不应再有过程内混填：" + issue.getMessage());
        }
    }

    // ---------- 工具 ----------

    private static List<FunctionSplitRow> process(String level3, String process, String user, String trigger,
                                                  String reuse, String skeleton) {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        for (int i = 0; i < skeleton.length(); i++) {
            String type = String.valueOf(skeleton.charAt(i));
            FunctionSplitRow row = new FunctionSplitRow();
            row.setLevel1Module("运维管理");
            row.setLevel2Module("资源管理");
            row.setLevel3Module(level3);
            row.setFunctionalUser(user);
            row.setTriggerEvent(trigger);
            row.setFunctionProcess(process);
            row.setMovementType(type);
            row.setSubProcessDescription(("E".equals(type) ? "接收" : "R".equals(type) ? "读取" : "W".equals(type) ? "保存" : "输出")
                    + process + "数据" + i);
            row.setDataGroup(process + "数据" + i);
            row.setDataAttributes("编码、名称");
            reuse(row, reuse);
            rows.add(row);
        }
        return rows;
    }

    private static void reuse(FunctionSplitRow row, String reuse) {
        row.setReuseDegree(reuse);
        row.setCfp("复用".equals(reuse) ? 1D / 3D : ("利旧".equals(reuse) ? 0D : 1D));
    }

    private static String tier(StrictEstimate estimate, String process) {
        for (ProcessTier item : estimate.getProcesses()) {
            if (process.equals(item.getFunctionProcess())) {
                return item.getTier();
            }
        }
        throw new AssertionError("缺少过程 " + process);
    }

    private static boolean hasRule(RuleValidationResponse response, String code) {
        for (RuleValidationIssue issue : response.getIssues()) {
            if (code.equals(issue.getRuleCode())) {
                return true;
            }
        }
        return false;
    }
}
