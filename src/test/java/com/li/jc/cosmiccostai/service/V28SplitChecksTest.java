package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.RuleCheckSummary;
import com.li.jc.cosmiccostai.dto.RuleValidationIssue;
import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规则 v2.8 确定性检查（V2/V3/V13/V15/V21/V35–V48）。 */
class V28SplitChecksTest {

    // ---------- 合规样例：三行骨架、真实功能用户、中文数据组、属性 ≥2 ----------

    @Test
    void compliantThreeRowProcessesHaveNoV28Issues() {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        rows.addAll(process("设备台账", "设备信息查询", "装维人员", "装维人员触发设备信息查询", "新增",
                m("E", "接收设备查询条件", "设备查询条件", "设备编码、所属地市"),
                m("R", "读取设备台账", "设备台账表", "设备编码、设备名称"),
                m("X", "输出设备信息列表", "设备信息列表", "设备编码、设备名称")));
        rows.addAll(process("设备台账", "设备信息新增", "装维人员", "装维人员触发设备信息新增", "新增",
                m("E", "接收设备新增信息", "设备新增信息", "设备编码、设备名称"),
                m("W", "保存设备信息", "设备台账表", "设备编码、设备名称"),
                m("X", "输出设备新增结果", "设备新增结果", "设备编码、新增状态")));
        rows.addAll(process("性能汇总", "小区性能数据定时汇总入库", "调度任务", "每日 02:00 调度", "复用",
                m("E", "接收定时调度指令", "定时调度指令", "调度时间、调度任务名称"),
                m("W", "保存小区性能汇总结果", "小区性能汇总表", "小区编码、汇总日期")));

        assertTrue(codes(rows).isEmpty(), describe(rows));
    }

    // ---------- 拆分约束 ----------

    @Test
    void v39FlagsOnlyNonSkeletonRowsOfFourRowProcess() {
        List<FunctionSplitRow> rows = process("设备台账", "设备信息修改", "装维人员", "装维人员触发设备信息修改", "新增",
                m("E", "接收设备修改信息", "设备修改信息", "设备编码、设备名称"),
                m("R", "读取设备原记录", "设备台账表", "设备编码、设备名称"),
                m("W", "保存设备信息", "设备台账表", "设备编码、设备名称"),
                m("X", "输出设备修改结果", "设备修改结果", "设备编码、修改状态"));

        List<RuleValidationIssue> issues = issues(rows, "V39");

        assertEquals(1, issues.size());
        assertEquals(2, issues.get(0).getRowIndex());
    }

    @Test
    void v39AllowsUserTriggeredExternalCallChain() {
        List<FunctionSplitRow> rows = process("终端诊断", "光猫远程诊断", "装维人员", "装维人员触发光猫远程诊断", "新增",
                m("E", "接收诊断请求", "诊断请求", "终端串码、诊断类型"),
                m("X", "发送诊断指令至终端管理平台", "诊断指令", "终端串码、诊断类型"),
                m("E", "获取终端管理平台同步响应数据", "诊断响应", "终端串码、诊断结论"),
                m("W", "保存诊断结论", "终端诊断记录表", "终端串码、诊断结论"),
                m("X", "输出诊断结论", "诊断结论", "终端串码、诊断结论"));

        assertTrue(issues(rows, "V39").isEmpty(), describe(rows));
    }

    @Test
    void v40FlagsMixedReuseButIgnoresTimerEntryAndLogWrite() {
        List<FunctionSplitRow> mixed = process("设备台账", "设备信息查询", "装维人员", "装维人员触发设备信息查询", "新增",
                m("E", "接收设备查询条件", "设备查询条件", "设备编码、所属地市"),
                m("R", "读取设备台账", "设备台账表", "设备编码、设备名称"),
                m("X", "输出设备信息列表", "设备信息列表", "设备编码、设备名称"));
        reuse(mixed.get(2), "复用");
        assertEquals(1, issues(mixed, "V40").size());
        assertEquals(1D + 1D + 1D / 3D, issues(mixed, "V40").get(0).getCfpImpact(), 1e-9);

        List<FunctionSplitRow> timer = process("性能汇总", "小区性能数据定时汇总入库", "调度任务", "每日 02:00 调度", "新增",
                m("E", "接收定时调度指令", "定时调度指令", "调度时间、调度任务名称"),
                m("W", "保存小区性能汇总结果", "小区性能汇总表", "小区编码、汇总日期"),
                m("W", "记录汇总日志", "汇总日志表", "任务名称、执行时间"));
        reuse(timer.get(0), "复用");
        reuse(timer.get(2), "复用");
        assertTrue(issues(timer, "V40").isEmpty(), describe(timer));
    }

    @Test
    void v41FlagsInternalEventButNotExternalInterfaceCallback() {
        List<FunctionSplitRow> internal = process("文件推送", "资源文件推送", "调度任务", "文件生成事件触发", "新增",
                m("E", "接收文件生成通知", "文件生成通知", "文件名称、生成时间"),
                m("X", "推送资源文件至对端SFTP", "资源文件", "文件名称、记录条数"));
        assertEquals(1, issues(internal, "V41").size());
        assertEquals(1, issues(internal, "V41").get(0).getRowIndex());

        List<FunctionSplitRow> callback = process("告警处理", "告警处理结果接收", "集团 EOMS", "集团 EOMS 通过接口回调", "新增",
                m("E", "接收告警处理结果", "告警处理结果", "告警编号、处理结论"),
                m("W", "保存告警处理结果", "告警结果表", "告警编号、处理结论"),
                m("X", "输出接收应答", "接收应答", "返回码、返回信息"));
        assertTrue(issues(callback, "V41").isEmpty(), describe(callback));
    }

    @Test
    void v42AndV43FlagUiWordsAndGranularityVariants() {
        List<FunctionSplitRow> ui = process("大屏", "告警卡片展示", "运维人员", "运维人员触发告警卡片展示", "新增",
                m("E", "接收卡片查询条件", "卡片查询条件", "地市、日期"),
                m("R", "读取告警统计", "告警统计表", "告警数量、地市"),
                m("X", "输出告警统计", "告警统计结果", "告警数量、地市"));
        assertFalse(issues(ui, "V42").isEmpty());

        List<FunctionSplitRow> granularity = process("性能汇总", "小区15分钟性能汇总", "调度任务", "每15分钟调度", "复用",
                m("E", "接收定时调度指令", "定时调度指令", "调度时间、调度任务名称"),
                m("W", "保存小区性能汇总结果", "小区性能汇总表", "小区编码、汇总时间"));
        assertFalse(issues(granularity, "V43").isEmpty());

        List<FunctionSplitRow> weather = process("天气", "按天气统计故障", "运维人员", "运维人员触发按天气统计故障", "新增",
                m("E", "接收统计条件", "统计条件", "日期、天气类型"),
                m("R", "读取故障记录", "故障记录表", "故障编号、天气类型"),
                m("X", "输出故障统计", "故障统计结果", "天气类型、故障数量"));
        assertTrue(issues(weather, "V43").isEmpty(), describe(weather));
    }

    @Test
    void v44FlagsDuplicateNamesAfterNormalizationButKeeps4gAnd5gDistinct() {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        rows.addAll(process("质差", "地市质差小区查询", "优化人员", "优化人员触发地市质差小区查询", "新增",
                m("E", "接收查询条件", "质差查询条件", "地市、日期"),
                m("R", "读取质差小区", "质差小区表", "小区编码、质差原因"),
                m("X", "输出质差小区", "质差小区列表", "小区编码、质差原因")));
        rows.addAll(process("质差", "区县质差小区查询", "优化人员", "优化人员触发区县质差小区查询", "新增",
                m("E", "接收查询条件", "质差查询条件", "区县、日期"),
                m("R", "读取质差小区", "质差小区表", "小区编码、质差原因"),
                m("X", "输出质差小区", "质差小区列表", "小区编码、质差原因")));
        assertEquals(2, issues(rows, "V44").size());

        List<FunctionSplitRow> generations = new ArrayList<FunctionSplitRow>();
        generations.addAll(process("网络", "4G小区信息查询", "优化人员", "优化人员触发4G小区信息查询", "新增",
                m("E", "接收查询条件", "小区查询条件", "地市、日期"),
                m("R", "读取4G小区", "4G小区表", "小区编码、频段"),
                m("X", "输出4G小区", "4G小区列表", "小区编码、频段")));
        generations.addAll(process("网络", "5G小区信息查询", "优化人员", "优化人员触发5G小区信息查询", "新增",
                m("E", "接收查询条件", "小区查询条件", "地市、日期"),
                m("R", "读取5G小区", "5G小区表", "小区编码、频段"),
                m("X", "输出5G小区", "5G小区列表", "小区编码、频段")));
        assertTrue(issues(generations, "V44").isEmpty(), describe(generations));
    }

    @Test
    void v45FlagsHumanTriggeredBatchAndTimerTriggeredQuery() {
        List<FunctionSplitRow> humanBatch = process("汇总", "工单数据汇总", "运维人员", "用户发起工单数据汇总", "新增",
                m("E", "接收汇总请求", "汇总请求", "统计日期、地市"),
                m("W", "保存工单汇总结果", "工单汇总表", "统计日期、工单数量"));
        assertFalse(issues(humanBatch, "V45").isEmpty());

        List<FunctionSplitRow> timerQuery = process("大屏", "告警数据查询", "调度任务", "每日定时触发", "新增",
                m("E", "接收定时调度指令", "定时调度指令", "调度时间、调度任务名称"),
                m("R", "读取告警数据", "告警数据表", "告警编号、告警级别"),
                m("X", "输出告警数据", "告警数据", "告警编号、告警级别"));
        assertFalse(issues(timerQuery, "V45").isEmpty());

        List<FunctionSplitRow> summaryQuery = process("汇总", "汇总数据查询", "运维人员", "运维人员触发汇总数据查询", "新增",
                m("E", "接收查询条件", "汇总查询条件", "统计日期、地市"),
                m("R", "读取汇总数据", "工单汇总表", "统计日期、工单数量"),
                m("X", "输出汇总数据", "汇总数据列表", "统计日期、工单数量"));
        assertTrue(issues(summaryQuery, "V45").isEmpty(), describe(summaryQuery));
    }

    @Test
    void v46V47V48FlagTemplateChainPlaceholderUserAndCrudSpread() {
        List<FunctionSplitRow> chain = new ArrayList<FunctionSplitRow>();
        for (String stage : Arrays.asList("性能数据稽核", "性能数据分类", "性能数据归档")) {
            chain.addAll(process("性能数据链", stage, "调度任务", "每日 02:00 调度", "复用",
                    m("E", "接收定时调度指令", "定时调度指令", "调度时间、调度任务名称"),
                    m("W", "保存" + stage + "结果", "性能数据表", "小区编码、统计日期")));
        }
        assertEquals(3, issues(chain, "V46").size());

        List<FunctionSplitRow> placeholder = process("设备台账", "设备信息查询", "发起者：功能用户\n接收者：系统",
                "功能用户触发设备信息查询", "新增",
                m("E", "接收设备查询条件", "设备查询条件", "设备编码、所属地市"),
                m("R", "读取设备台账", "设备台账表", "设备编码、设备名称"),
                m("X", "输出设备信息列表", "设备信息列表", "设备编码、设备名称"));
        assertEquals(1, issues(placeholder, "V47").size());

        List<FunctionSplitRow> crud = new ArrayList<FunctionSplitRow>();
        for (String op : Arrays.asList("查询", "新增", "修改", "删除", "导入", "导出", "详情查看")) {
            crud.addAll(process("存量台账", "站点台账" + op, "代维调度员", "代维调度员触发站点台账" + op, "新增",
                    m("E", "接收站点台账" + op + "信息", "站点台账请求", "站点编码、站点名称"),
                    m("X", "输出站点台账" + op + "结果", "站点台账结果", "站点编码、站点名称")));
        }
        assertEquals(7, issues(crud, "V48").size());
        assertEquals(7, issues(crud, "V38").size());
    }

    // ---------- 表达层 ----------

    @Test
    void v35V36V37FlagExpressionLayerButAllowCommonFormatWords() {
        List<FunctionSplitRow> rows = process("性能", "开启小区性能监控", "优化人员", "优化人员触发开启小区性能监控", "新增",
                m("E", "系统按条件接收监控设置", "ods.ODS_FT_QU_HGUPERIODIC_MI15", "小区编码"),
                m("W", "保存监控设置", "小区监控配置表", "小区编码、监控开关"),
                m("X", "输出Excel导入结果", "Excel导入结果", "成功数量、失败数量"));

        assertEquals(1, issues(rows, "V35").size());
        assertEquals(2, issues(rows, "V36").size()); // 子过程非动词开头 + 过程名状态词会在 V36 中分别计入
        assertEquals(1, issues(rows, "V37").size());
    }

    // ---------- 不成立的移动/过程 ----------

    @Test
    void flagsReadBackRenderWriteTemplateRowsSchedulerInfraAndOptimizeTitleNew() {
        List<FunctionSplitRow> readBack = process("工单", "工单派发", "代维调度员", "代维调度员触发工单派发", "新增",
                m("E", "接收派发信息", "派发信息", "工单编号、处理人"),
                m("W", "保存派发结果", "工单主表", "工单编号、处理人"),
                m("R", "读取工单主表", "工单主表", "工单编号、处理人"),
                m("X", "输出派发结果", "派发结果", "工单编号、派发状态"));
        assertEquals(1, issues(readBack, "V2").size());

        List<FunctionSplitRow> render = process("报表", "告警趋势查询", "运维人员", "运维人员触发告警趋势查询", "新增",
                m("E", "接收查询条件", "趋势查询条件", "地市、日期"),
                m("R", "读取告警统计", "告警统计表", "地市、告警数量"),
                m("W", "构建图表数据集", "趋势图数据", "日期、告警数量"),
                m("X", "输出告警趋势", "告警趋势", "日期、告警数量"));
        assertEquals(1, issues(render, "V3").size());

        List<FunctionSplitRow> template = process("设备台账", "设备信息新增", "装维人员", "装维人员触发设备信息新增", "新增",
                m("E", "接收设备新增信息", "设备新增信息", "设备编码、设备名称"),
                m("R", "读取设备相关数据", "设备台账表", "设备编码、设备名称"),
                m("W", "保存设备信息", "设备台账表", "设备编码、设备名称"),
                m("X", "返回设备处理结果", "处理结果", "处理状态、处理说明"));
        assertEquals(2, issues(template, "V13").size());

        List<FunctionSplitRow> infra = process("调度", "获取分布式锁", "调度任务", "每日 02:00 调度", "复用",
                m("E", "接收定时调度指令", "定时调度指令", "调度时间、调度任务名称"),
                m("W", "保存锁记录", "分布式锁表", "锁名称、持有者"));
        assertEquals(1, issues(infra, "V15").size());

        List<FunctionSplitRow> optimize = process("工单时限优化", "工单时限查询", "代维调度员", "代维调度员触发工单时限查询", "新增",
                m("E", "接收查询条件", "时限查询条件", "工单类型、地市"),
                m("R", "读取工单时限", "工单时限表", "工单类型、时限"),
                m("X", "输出工单时限", "工单时限列表", "工单类型、时限"));
        assertEquals(3, issues(optimize, "V21").size());
    }

    // ---------- 汇总：规则编号、涉及 CFP、按影响排序 ----------

    @Test
    void responseSummarizesRuleCodesByCfpImpactDescending() {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        rows.addAll(process("大屏", "告警卡片展示", "运维人员", "运维人员触发告警卡片展示", "新增",
                m("E", "接收卡片查询条件", "卡片查询条件", "地市、日期"),
                m("R", "读取告警统计", "告警统计表", "告警数量、地市"),
                m("X", "输出告警统计", "告警统计结果", "告警数量、地市")));
        rows.addAll(process("台账", "站点信息修改", "代维调度员", "代维调度员触发站点信息修改", "复用",
                m("E", "接收站点修改信息", "站点修改信息", "站点编码、站点名称"),
                m("R", "读取站点原记录", "站点台账表", "站点编码、站点名称"),
                m("W", "保存站点信息", "站点台账表", "站点编码、站点名称"),
                m("X", "输出站点修改结果", "站点修改结果", "站点编码、修改状态")));

        RuleValidationResponse response = new RuleValidationService().validate(rows);
        List<RuleCheckSummary> summaries = response.getRuleSummaries();

        RuleCheckSummary v42 = summary(summaries, "V42");
        RuleCheckSummary v39 = summary(summaries, "V39");
        assertEquals(1, v42.getRowCount());
        assertEquals(3D, v42.getCfpImpact(), 1e-9);
        assertEquals(1, v39.getRowCount());
        assertEquals(0.33D, v39.getCfpImpact(), 1e-9);
        assertEquals("过程名含 UI/步骤词", v42.getTitle());
        for (int i = 1; i < summaries.size(); i++) {
            assertTrue(summaries.get(i - 1).getCfpImpact() >= summaries.get(i).getCfpImpact());
        }
        for (RuleValidationIssue issue : response.getIssues()) {
            if ("V42".equals(issue.getRuleCode())) {
                assertTrue(issue.getMessage().startsWith("V42 "));
                assertEquals(1, issue.getRowIndex());
                assertEquals(3D, issue.getCfpImpact(), 1e-9);
            }
        }
    }

    @Test
    void splitConstraintIssuesBlockAiGeneratedRowsButExpressionLayerDoesNot() {
        RuleValidationService service = new RuleValidationService();
        List<FunctionSplitRow> ui = process("大屏", "告警卡片展示", "运维人员", "运维人员触发告警卡片展示", "新增",
                m("E", "接收卡片查询条件", "卡片查询条件", "地市、日期"),
                m("R", "读取告警统计", "告警统计表", "告警数量、地市"),
                m("X", "输出告警统计", "告警统计结果", "告警数量、地市"));
        List<FunctionSplitRow> expression = process("设备台账", "设备信息查询", "装维人员", "装维人员触发设备信息查询", "新增",
                m("E", "接收设备查询条件", "设备查询条件", "设备编码"),
                m("R", "读取设备台账", "设备台账表", "设备编码、设备名称"),
                m("X", "输出设备信息列表", "设备信息列表", "设备编码、设备名称"));

        assertFalse(service.generatedBlockingIssues(ui).isEmpty());
        assertTrue(service.generatedBlockingIssues(expression).isEmpty());
    }

    // ---------- 工具 ----------

    private static String[] m(String type, String description, String group, String attributes) {
        return new String[]{type, description, group, attributes};
    }

    private static List<FunctionSplitRow> process(String level3, String process, String user, String trigger,
                                                  String reuse, String[]... movements) {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        for (String[] movement : movements) {
            FunctionSplitRow row = new FunctionSplitRow();
            row.setLevel1Module("运维管理");
            row.setLevel2Module("资源管理");
            row.setLevel3Module(level3);
            row.setFunctionalUser(user);
            row.setTriggerEvent(trigger);
            row.setFunctionProcess(process);
            row.setMovementType(movement[0]);
            row.setSubProcessDescription(movement[1]);
            row.setDataGroup(movement[2]);
            row.setDataAttributes(movement[3]);
            reuse(row, reuse);
            rows.add(row);
        }
        return rows;
    }

    private static void reuse(FunctionSplitRow row, String reuse) {
        row.setReuseDegree(reuse);
        row.setCfp("复用".equals(reuse) ? 1D / 3D : ("利旧".equals(reuse) ? 0D : 1D));
    }

    private static List<RuleValidationIssue> issues(List<FunctionSplitRow> rows, String code) {
        List<RuleValidationIssue> result = new ArrayList<RuleValidationIssue>();
        for (RuleValidationIssue issue : V28SplitChecks.check(rows)) {
            if (code.equals(issue.getRuleCode())) {
                result.add(issue);
            }
        }
        return result;
    }

    private static Set<String> codes(List<FunctionSplitRow> rows) {
        Set<String> codes = new LinkedHashSet<String>();
        for (RuleValidationIssue issue : V28SplitChecks.check(rows)) {
            codes.add(issue.getRuleCode());
        }
        return codes;
    }

    private static RuleCheckSummary summary(List<RuleCheckSummary> summaries, String code) {
        for (RuleCheckSummary summary : summaries) {
            if (code.equals(summary.getRuleCode())) {
                return summary;
            }
        }
        throw new AssertionError("缺少汇总 " + code);
    }

    private static String describe(List<FunctionSplitRow> rows) {
        StringBuilder builder = new StringBuilder();
        for (RuleValidationIssue issue : V28SplitChecks.check(rows)) {
            builder.append(issue.getRowIndex()).append(':').append(issue.getMessage()).append(" | ");
        }
        return builder.toString();
    }
}
