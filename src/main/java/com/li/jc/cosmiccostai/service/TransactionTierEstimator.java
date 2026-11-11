package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.ProcessTier;
import com.li.jc.cosmiccostai.dto.StrictEstimate;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 事务三档法严口径预估（规则 v2.8 第 3 节，与 cosmic-cost-workbench 的 tx_apply 同骨架口径）。
 *
 * <p>步骤：成立性（不成立 → 0）→ 去重（加工链变体、同模块重名）→ 定档（新 / 定 / 改）→ 骨架计量。
 * 定档用关键词与表内结构近似“什么算新对象”：一个三级模块视为一个业务对象，
 * 首个查询、首个新增、首个接口算新建；关联操作、优化/改造类标题、送审已填复用的过程算改造。
 * 需要通读需求才能判断的新对象粒度不在此处推断，结果只作预估。</p>
 */
final class TransactionTierEstimator {

    static final String NEW = "新";
    static final String TIMER_NEW = "定";
    static final String MODIFY = "改";
    static final String ZERO = "0";

    private static final double THIRD = 1D / 3D;
    private static final Pattern PAGE_ACTION = Pattern.compile(
            "翻页|勾选|图层切换|自动填充|高亮渲染|重置查询条件|清空条件|每页条数|页码跳转|下拉框|选项加载|打开弹窗|打开页面|关闭弹窗|返回列表|取消操作");
    private static final Pattern QUERY = Pattern.compile("查询|查看|检索|列表|统计|分析|监控|展示|呈现");
    private static final Pattern RELATED = Pattern.compile("详情|导出|下载|预览|导入|修改|编辑|删除|启用|停用|启停|审批|审核|复制|移动|重命名|发布");
    private static final Pattern CREATE = Pattern.compile("新增|新建|创建|添加|录入|登记");
    private static final Pattern INTERFACE_TRIGGER = Pattern.compile("接口|调用本系统|推送至本系统");
    private static final Pattern HUMAN_USER = Pattern.compile("人员|管理员|用户|员|经理");
    private static final Pattern SYSTEM_USER = Pattern.compile("系统|平台|EOMS|网关");
    private static final Pattern SCHEDULER_USER = Pattern.compile("^(调度任务|定时任务|调度程序|定时调度|调度中心|时钟)");

    private TransactionTierEstimator() {
    }

    static StrictEstimate estimate(List<FunctionSplitRow> rows) {
        StrictEstimate estimate = new StrictEstimate();
        if (rows == null || rows.isEmpty()) {
            return estimate;
        }
        Map<String, List<Integer>> processes = groupProcesses(rows);
        Map<String, String> firstVariantModule = new HashMap<String, String>();
        Set<String> seenQuery = new HashSet<String>();
        Set<String> seenCreate = new HashSet<String>();
        Set<String> seenInterface = new HashSet<String>();
        Set<String> levelHasAnchor = level3WithQueryOrCreate(rows, processes);
        Set<String> levelUsedFallback = new HashSet<String>();

        double declared = 0D;
        double strict = 0D;
        for (List<Integer> indexes : processes.values()) {
            FunctionSplitRow head = rows.get(indexes.get(0));
            String process = safe(head.getFunctionProcess());
            String trigger = safe(head.getTriggerEvent());
            String user = initiator(head.getFunctionalUser());
            String level1 = safe(head.getLevel1Module());
            String level3Key = level1 + "\u001f" + safe(head.getLevel2Module()) + "\u001f" + safe(head.getLevel3Module());
            String modules = level1 + " " + safe(head.getLevel2Module()) + " " + safe(head.getLevel3Module());
            boolean scheduled = V28SplitChecks.isScheduled(trigger) || SCHEDULER_USER.matcher(user).find();

            String tier;
            String reason;
            String variant = V28SplitChecks.normalizeVariant(process);
            String firstModule = firstVariantModule.get(variant);
            if (allReuse(rows, indexes, "利旧")) {
                tier = ZERO;
                reason = "送审已判利旧";
            } else if (V28SplitChecks.isUiProcess(process) || PAGE_ACTION.matcher(process).find()) {
                tier = ZERO;
                reason = "页面交互/UI 步骤";
            } else if (V28SplitChecks.isSchedulerInfrastructure(process)) {
                tier = ZERO;
                reason = "调度基础设施";
            } else if (V28SplitChecks.isInternalTrigger(trigger)) {
                tier = ZERO;
                reason = "内部事件二段过程";
            } else if (V28SplitChecks.isTemplateStage(process)) {
                tier = ZERO;
                reason = "模板链步骤";
            } else if (firstModule != null && (scheduled || firstModule.equals(level1))) {
                tier = ZERO;
                reason = scheduled ? "加工链变体" : "同模块重复过程";
            } else if (firstModule != null) {
                tier = MODIFY;
                reason = "跨模块同名过程（对象族变体）";
            } else if (allReuse(rows, indexes, "复用")) {
                tier = MODIFY;
                reason = "送审已填复用";
            } else if (V28SplitChecks.isOptimizeTitle(modules)) {
                tier = MODIFY;
                reason = "优化/改造类标题（O）";
            } else if (scheduled) {
                tier = TIMER_NEW;
                reason = "定时链首";
            } else if (INTERFACE_TRIGGER.matcher(trigger).find()
                    || (SYSTEM_USER.matcher(user).find() && !HUMAN_USER.matcher(user).find())) {
                if (seenInterface.add(level3Key)) {
                    tier = NEW;
                    reason = "对端首个接口";
                } else {
                    tier = MODIFY;
                    reason = "同对象第 2 个及以后接口";
                }
            } else if (CREATE.matcher(process).find() && seenCreate.add(level3Key)) {
                tier = NEW;
                reason = "业务对象首个新增";
            } else if (QUERY.matcher(process).find() && !RELATED.matcher(process).find() && seenQuery.add(level3Key)) {
                tier = NEW;
                reason = "业务对象首个查询";
            } else if (!levelHasAnchor.contains(level3Key) && levelUsedFallback.add(level3Key)) {
                tier = NEW;
                reason = "业务对象首个写操作（模块无查询/新增）";
            } else {
                tier = MODIFY;
                reason = "关联操作";
            }
            if (firstModule == null && !ZERO.equals(tier)) {
                firstVariantModule.put(variant, level1);
            }

            double processDeclared = 0D;
            for (Integer index : indexes) {
                processDeclared += rows.get(index).getCfp();
            }
            double processStrict = skeletonCfp(rows, indexes, tier);

            ProcessTier item = new ProcessTier();
            item.setRowIndex(indexes.get(0) + 1);
            item.setFunctionProcess(process);
            item.setLevel3Module(safe(head.getLevel3Module()));
            item.setTier(tier);
            item.setReason(reason);
            item.setRowCount(indexes.size());
            item.setDeclaredCfp(round(processDeclared));
            item.setStrictCfp(round(processStrict));
            estimate.getProcesses().add(item);

            declared += processDeclared;
            strict += processStrict;
            if (NEW.equals(tier)) {
                estimate.setNewCount(estimate.getNewCount() + 1);
            } else if (TIMER_NEW.equals(tier)) {
                estimate.setTimerNewCount(estimate.getTimerNewCount() + 1);
            } else if (MODIFY.equals(tier)) {
                estimate.setModifyCount(estimate.getModifyCount() + 1);
            } else {
                estimate.setZeroCount(estimate.getZeroCount() + 1);
            }
        }
        int transactions = estimate.getNewCount() + estimate.getTimerNewCount() + estimate.getModifyCount();
        estimate.setProcessCount(processes.size());
        estimate.setTransactionCount(transactions);
        estimate.setDeclaredCfp(round(declared));
        estimate.setStrictCfp(round(strict));
        estimate.setDeclaredPerTransaction(transactions == 0 ? 0D : round(declared / transactions));
        estimate.setNewShare(transactions == 0 ? 0D
                : round((estimate.getNewCount() + estimate.getTimerNewCount()) / (double) transactions));
        return estimate;
    }

    /** 过程键 → 档位，供复用度按过程统一使用。 */
    static Map<String, String> tiersByProcessKey(List<FunctionSplitRow> rows) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        StrictEstimate estimate = estimate(rows);
        for (ProcessTier tier : estimate.getProcesses()) {
            result.put(processKey(rows, tier.getRowIndex() - 1), tier.getTier());
        }
        return result;
    }

    static Map<String, List<Integer>> groupProcesses(List<FunctionSplitRow> rows) {
        Map<String, List<Integer>> processes = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < rows.size(); i++) {
            String key = processKey(rows, i);
            List<Integer> group = processes.get(key);
            if (group == null) {
                group = new ArrayList<Integer>();
                processes.put(key, group);
            }
            group.add(i);
        }
        return processes;
    }

    static String processKey(List<FunctionSplitRow> rows, int index) {
        FunctionSplitRow row = rows.get(index);
        return StringUtils.hasText(safe(row.getFunctionProcess())) ? FunctionProcessKey.of(row) : "__ROW_" + index;
    }

    /** 骨架计量：新 → 骨架行各 1；改 → 各 1/3；定 → 首 E 1/3、其余骨架行 1；0 → 0。 */
    private static double skeletonCfp(List<FunctionSplitRow> rows, List<Integer> indexes, String tier) {
        if (ZERO.equals(tier)) {
            return 0D;
        }
        Set<Integer> positions = V28SplitChecks.skeletonPositions(rows, indexes);
        double total = 0D;
        for (Integer position : positions) {
            if (NEW.equals(tier)) {
                total += 1D;
            } else if (TIMER_NEW.equals(tier)) {
                total += position == 0 ? THIRD : 1D;
            } else {
                total += THIRD;
            }
        }
        return total;
    }

    private static Set<String> level3WithQueryOrCreate(List<FunctionSplitRow> rows, Map<String, List<Integer>> processes) {
        Set<String> result = new HashSet<String>();
        for (List<Integer> indexes : processes.values()) {
            FunctionSplitRow head = rows.get(indexes.get(0));
            String process = safe(head.getFunctionProcess());
            if (CREATE.matcher(process).find() || (QUERY.matcher(process).find() && !RELATED.matcher(process).find())) {
                result.add(safe(head.getLevel1Module()) + "\u001f" + safe(head.getLevel2Module())
                        + "\u001f" + safe(head.getLevel3Module()));
            }
        }
        return result;
    }

    /** 功能用户“发起者：X 换行 接收者：Y”只取发起者；没有该格式时取全文。 */
    static String initiator(String functionalUser) {
        String value = safe(functionalUser);
        int start = value.indexOf("发起者");
        if (start < 0) {
            return value;
        }
        String rest = value.substring(start + 3);
        if (rest.startsWith("：") || rest.startsWith(":")) {
            rest = rest.substring(1);
        }
        int end = rest.indexOf('\n');
        int receiver = rest.indexOf("接收者");
        if (receiver >= 0 && (end < 0 || receiver < end)) {
            end = receiver;
        }
        return (end >= 0 ? rest.substring(0, end) : rest).trim();
    }

    private static boolean allReuse(List<FunctionSplitRow> rows, List<Integer> indexes, String value) {
        for (Integer index : indexes) {
            if (!value.equals(safe(rows.get(index).getReuseDegree()))) {
                return false;
            }
        }
        return true;
    }

    private static double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
