package com.li.jc.cosmiccostai.service;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Movement profiles distilled from the accepted COSMIC writing examples.
 *
 * <p>A profile is selected from the primary action plus the trigger context.
 * This is important for interface and scheduled processes: the same word such
 * as "同步" can mean reading data out of this system or writing received data
 * into this system.</p>
 */
final class ActionMovementRule {

    private static final Profile QUERY = profile(
            "查询", setOf("R", "X"), setOf("W"), 1, 0, 1);
    private static final Profile CREATE = profile(
            "新增", setOf("W", "X"), setOf("R"), 0, 1, 1);
    private static final Profile MODIFY = profile(
            "修改", setOf("W", "X"), emptySet(), null, 1, 1);
    private static final Profile DELETE = profile(
            "删除", setOf("W", "X"), setOf("R"), 0, 1, 1);
    private static final Profile IMPORT = profile(
            "导入", setOf("R", "W", "X"), emptySet(), 1, 1, 1);
    private static final Profile EXPORT = profile(
            "导出", setOf("R", "X"), emptySet(), 1, null, 1);
    private static final Profile WORKFLOW = profile(
            "流程派发/审批", setOf("R", "W", "X"), emptySet(), 1, 1, 1);
    private static final Profile SCHEDULED_SUMMARY = profile(
            "定时汇总/统计", setOf("R", "W"), emptySet(), 1, 1, 0);
    private static final Profile FILE_COLLECTION = profile(
            "定时文件采集", setOf("W"), emptySet(), 0, 1, 0);
    private static final Profile MESSAGE_DELIVERY = profile(
            "发送/推送", setOf("R", "X"), emptySet(), 1, null, 1);
    private static final Profile CALLED_QUERY_INTERFACE = profile(
            "被调用方查询接口", setOf("R", "X"), setOf("W"), 1, 0, 1);
    private static final Profile CALLED_SYNC_INTERFACE = profile(
            "被调用方同步接口", setOf("W", "X"), setOf("R"), 0, 1, 1);
    private static final Profile CALLER_PULL_INTERFACE = profile(
            "调用方拉取同步", setOf("W", "X"), emptySet(), 0, 1, 1);
    private static final Profile CALLER_PUSH_INTERFACE = profile(
            "调用方推送数据", setOf("R", "X"), emptySet(), 1, 0, 1);
    private static final Profile CALLER_QUERY_REQUEST = profile(
            "调用方发起外部查询", setOf("X"), emptySet(), 0, 0, 1);

    private ActionMovementRule() {
    }

    static Match resolve(String functionProcess) {
        return resolve(functionProcess, "");
    }

    static Match resolve(String functionProcess, String triggerEvent) {
        return resolve(functionProcess, triggerEvent, "");
    }

    static Match resolve(String functionProcess, String triggerEvent, String evidence) {
        List<Match> matches = resolveAll(functionProcess, triggerEvent, evidence);
        return matches.isEmpty() ? null : matches.get(0);
    }

    static List<Match> resolveAll(String functionProcess, String triggerEvent, String evidence) {
        String process = safe(functionProcess);
        String trigger = safe(triggerEvent);
        String context = process + " " + trigger + " " + safe(evidence);
        boolean scheduled = containsAny(context, "定时", "时钟", "调度");
        boolean interfaceTriggered = containsAny(trigger, "接口");
        List<Match> matches = new ArrayList<Match>();

        if (isOutboundQueryRequest(process, context)) {
            matches.add(new Match(CALLER_QUERY_REQUEST, firstKeyword(context, "查询请求", "请求查询", "查询")));
            return matches;
        }
        if (containsAny(process, "查询数据类")) {
            if (scheduled) {
                matches.add(new Match(CALLER_PULL_INTERFACE, "查询数据类"));
                return matches;
            }
            matches.add(new Match(CALLED_QUERY_INTERFACE, "查询数据类"));
            return matches;
        }
        if (containsAny(process, "同步数据类")) {
            if (scheduled || containsAny(process, "推送")) {
                matches.add(new Match(CALLER_PUSH_INTERFACE, "同步数据类"));
                return matches;
            }
            if (interfaceTriggered || containsAny(process, "同步")) {
                matches.add(new Match(CALLED_SYNC_INTERFACE, "同步数据类"));
                return matches;
            }
        }
        if (interfaceTriggered && containsAny(process, "接收", "上报", "回调", "同步")
                && containsAny(process, "结果", "数据", "状态", "信息", "回执")) {
            matches.add(new Match(CALLED_SYNC_INTERFACE,
                    firstKeyword(process, "回调", "上报", "接收", "同步")));
            return matches;
        }
        if (scheduled && containsAny(process, "统计", "汇总")) {
            addUnique(matches, SCHEDULED_SUMMARY, firstKeyword(process, "统计", "汇总"));
        }
        if (scheduled && containsAny(process, "采集", "下载")) {
            addUnique(matches, FILE_COLLECTION, firstKeyword(process, "采集", "下载"));
        }
        if (hasWorkflowAction(process)) {
            addUnique(matches, WORKFLOW,
                    firstKeyword(process, "派发", "派单", "审批", "审核", "确认", "驳回", "撤回", "下发", "发布"));
        }
        if (containsAny(process, "导入")) {
            addUnique(matches, IMPORT, "导入");
        }
        if (containsAny(process, "导出", "下载", "预览")) {
            addUnique(matches, EXPORT, firstKeyword(process, "导出", "下载", "预览"));
        }
        if (containsAny(process, "新增", "新建", "创建", "添加")) {
            addUnique(matches, CREATE, firstKeyword(process, "新增", "新建", "创建", "添加"));
        }
        if (containsAny(process, "删除", "移除", "废止", "作废", "注销")) {
            addUnique(matches, DELETE, firstKeyword(process, "删除", "移除", "废止", "作废", "注销"));
        }
        if (containsAny(process, "修改", "编辑", "更新", "变更", "调整", "配置", "绑定", "解绑",
                "启用", "停用", "恢复", "清除", "关闭", "解锁", "归档")) {
            addUnique(matches, MODIFY, firstKeyword(process, "修改", "编辑", "更新", "变更", "调整", "配置",
                    "绑定", "解绑", "启用", "停用", "恢复", "清除", "关闭", "解锁", "归档"));
        }
        if (containsAny(process, "发送", "推送", "通知", "短信")) {
            addUnique(matches, MESSAGE_DELIVERY, firstKeyword(process, "发送", "推送", "通知", "短信"));
        }
        if (containsAny(process, "查询", "查看", "检索", "搜索", "列表", "详情")) {
            addUnique(matches, QUERY, firstKeyword(process, "查询", "查看", "检索", "搜索", "列表", "详情"));
        }
        return matches;
    }

    static boolean hasMultipleIndependentActions(String functionProcess, String triggerEvent, String evidence) {
        String process = safe(functionProcess);
        String context = process + " " + safe(triggerEvent) + " " + safe(evidence);
        if (isOutboundQueryRequest(process, context)) {
            return false;
        }
        int families = 0;
        families += containsAny(process, "新增", "新建", "创建", "添加") ? 1 : 0;
        families += containsAny(process, "删除", "移除", "废止", "作废", "注销") ? 1 : 0;
        families += containsAny(process, "修改", "编辑", "更新", "变更", "调整", "配置", "绑定", "解绑",
                "启用", "停用", "恢复", "清除", "关闭", "解锁", "归档") ? 1 : 0;
        families += containsAny(process, "查询", "查看", "检索", "搜索", "列表", "详情") ? 1 : 0;
        families += containsAny(process, "导入") ? 1 : 0;
        families += containsAny(process, "导出", "预览") ? 1 : 0;
        families += hasWorkflowAction(process) ? 1 : 0;
        return families > 1;
    }

    private static boolean hasWorkflowAction(String process) {
        String actionContext = neutralizeWorkflowObjectTerms(process);
        if (containsAny(actionContext, "派发", "派单", "审批", "审核", "确认", "驳回", "撤回", "下发")) {
            return true;
        }
        if (!actionContext.contains("发布")) {
            return false;
        }
        return !containsAny(actionContext, "发布失败", "发布异常", "发布告警", "发布状态", "未发布", "无法发布");
    }

    /**
     * Words such as "派发" and "审批" can be either real workflow actions or part of
     * the managed business object's name. When another explicit CRUD/import/export
     * action manages objects such as "盘点单派发规则", the embedded workflow word
     * must not be counted as a second independent action.
     */
    private static String neutralizeWorkflowObjectTerms(String process) {
        if (!containsAny(process,
                "新增", "新建", "创建", "添加",
                "删除", "移除", "废止", "作废", "注销",
                "修改", "编辑", "更新", "变更", "调整", "配置", "绑定", "解绑",
                "启用", "停用", "恢复", "清除", "关闭", "解锁", "归档",
                "查询", "查看", "检索", "搜索", "列表", "详情",
                "导入", "导出", "下载", "预览")) {
            return process;
        }
        String result = process;
        String[] workflowWords = {"派发", "派单", "审批", "审核", "确认", "驳回", "撤回", "下发", "发布"};
        String[] objectSuffixes = {"规则", "配置", "策略", "模板", "记录", "状态", "结果", "列表", "详情"};
        for (String workflow : workflowWords) {
            if (hasExplicitWorkflowConnector(process, workflow)) {
                continue;
            }
            for (String suffix : objectSuffixes) {
                result = result.replace(workflow + suffix, suffix);
            }
        }
        return result;
    }

    private static boolean hasExplicitWorkflowConnector(String process, String workflow) {
        return containsAny(process,
                "并" + workflow, "并且" + workflow, "同时" + workflow,
                "随后" + workflow, "然后" + workflow, "再" + workflow,
                "后" + workflow, "执行" + workflow, "进行" + workflow,
                workflow + "并", workflow + "后再", workflow + "后");
    }

    static String canonicalPromptRules() {
        return "统一动作组合（AI 拆分、初审、深度审核、保存和导出使用同一口径）：\n"
                + "- 用户查询 E-R-X；新增/删除 E-W-X；修改和导入 E-R-W-X。查询通常没有 W，新增/删除通常没有 R。\n"
                + "- 新建/创建/添加按新增处理；移除/作废/注销按删除处理。只有明确关联读取其他持久化数据组时，新增/删除才扩展 R。\n"
                + "- 修改类通常为 E-R-W-X；编辑/更新/业务配置/绑定解绑/启停/恢复清除/关闭按修改处理。若原文证明无需读取持久化原数据即可直接更新，可为 E-W-X，禁止为凑模板补 R。\n"
                + "- 流程派发、发布、审批、审核、撤回按 E-R-W-X 处理。\n"
                + "- 新疆项目中，查询、导出、发送等以 R/X 为主的功能或业务对象名称同时含“派发/派单”等词时，不得把动作模板机械合并后强制补 W，也不提示“派发/查询”“派发/导出”等组合缺少 W；只有独立派发功能，或原文明示更新派发状态、写入派发结果等持久化动作时才计 W。\n"
                + "- 临时生成且下载后删除为 E-R-X，写入服务器并保留为 E-R-W-X；即文件写入服务器并保留为 E-R-W-X。\n"
                + "- 定时文件采集直接保存文件并写业务表时，也可合并为 E-W-W。\n"
                + "- 定时统计/汇总并保存：E-R-W；同时发送/推送/通知时必须增加 X，以 E-R-W-X 结束。\n"
                + "- 发送/推送/短信通知：E-R-X；明确保存发送结果、记录或状态时为 E-R-W-X。\n"
                + "- 被调用方：查询接口 E-R-X，接收同步数据 E-W-X；即被调用查询 E-R-X，被调用接收同步数据 E-W-X。回调/上报接口同样按真实写入和响应处理。\n"
                + "- 调用方定时拉取并落库：E-W-X；读取本系统数据并向外推送：E-R-X。E 已携带查询所需参数并直接通过 X 发出请求时可从 E-X 起步，不得仅因功能名含“查询”补 R。\n"
                + "- 本系统向外部平台发送 X 后，外部平台在同一用户目标内同步返回数据时，响应进入本系统可再次记 E；可形成 E-X-E-W-X，串联多个外部系统时可出现多组 X-E。\n"
                + "- 外部平台稍后以回调/上报接口触发本系统时属于新的独立触发，必须另拆功能过程；不得把异步回调伪装为同步响应 E。\n"
                + "- E 描述按真实发起方使用自然业务语言：用户/UI 主动触发写“发起/提交”，外部平台上报或回调才写“接收”，X 后同步响应写“获取同步响应数据”；禁止机械拼接“接收+功能过程名+请求”。\n"
                + "- 不得只根据“同步、导出、采集、发送”等名称机械禁止或补充 R/W/X。\n"
                + "- 新疆项目暂不因功能过程名称同时出现多个动作词直接提示风险，也不因出现“配置”提示表达风险；只有原文能够证明存在不同触发、不同用户目标或不同业务结果时，才拆成多个功能过程。\n"
                + "- 一个 R/W 数据移动只能对应一个逻辑持久化数据组。同一触发、同一用户目标内，原文明示读取或更新多个不同业务对象、业务表、台账或记录时，功能过程保持一个，但必须按对象分别拆成多个 R/W；同一对象仅更新多个字段仍只计一个 W。"
                + "例如“更新调拨分派单状态，并更新库存及全生命周期流转记录”应分别写调拨分派单、库存、全生命周期流转记录 3 个 W，不得合并到一个 W 的数据组中。\n"
                + "- 顺序要求：首行必须是初始触发 E，末行为 W/X；禁止无依据的连续 E。X 后允许有依据的同步外部响应 E，其他 R/W/X 按真实业务依赖排序。\n"
                + "- 不设置最多 4 行限制；5、6、12 行等长链路只要每一移动都有原文、边界和同一业务目标依据即可保留。\n";
    }

    private static void addUnique(List<Match> matches, Profile profile, String keyword) {
        for (Match match : matches) {
            if (match.profile == profile) {
                return;
            }
        }
        matches.add(new Match(profile, keyword));
    }

    private static boolean isOutboundQueryRequest(String process, String context) {
        boolean queryRequest = containsAny(process + " " + context, "查询请求", "请求查询", "调用查询",
                "查询接口");
        boolean outboundAction = containsAny(context, "发送", "调用", "推送", "同步至", "输出至");
        boolean externalDirection = containsAny(context, "外部平台", "外部系统", "第三方平台", "第三方系统",
                "三方平台", "三方系统")
                || (context.contains("向") && containsAny(context, "平台", "系统", "接口"));
        return queryRequest && outboundAction && externalDirection;
    }

    static boolean hasPersistentExportEvidence(String text) {
        String value = safe(text);
        return containsAny(value, "存储到服务器", "保存到服务器", "服务器保存", "服务器存储",
                "持久化", "下载后不删除", "文件不删除", "保留导出文件", "导出文件表");
    }

    static boolean hasMessageRecordEvidence(String text) {
        String value = safe(text);
        return containsAny(value, "短信结果", "发送记录", "发送结果", "发送状态",
                "通知记录", "通知结果", "消息记录", "消息结果");
    }

    static boolean hasCombinedCollectionEvidence(String text) {
        String value = safe(text);
        boolean file = containsAny(value, "文件");
        boolean businessData = containsAny(value, "写入数据", "写入业务", "数据表", "数据入库");
        return file && businessData;
    }

    private static String firstKeyword(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return keyword;
            }
        }
        return "";
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static Profile profile(String label,
                                   Set<String> requiredMovements,
                                   Set<String> discouragedMovements,
                                   Integer recommendedReadCount,
                                   Integer recommendedWriteCount,
                                   Integer recommendedExitCount) {
        return new Profile(label, requiredMovements, discouragedMovements,
                recommendedReadCount, recommendedWriteCount, recommendedExitCount);
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }

    private static Set<String> emptySet() {
        return Collections.emptySet();
    }

    static final class Match {
        private final Profile profile;
        private final String keyword;

        private Match(Profile profile, String keyword) {
            this.profile = profile;
            this.keyword = keyword;
        }

        Profile getProfile() {
            return profile;
        }

        String getKeyword() {
            return keyword;
        }
    }

    static final class Profile {
        private final String label;
        private final Set<String> requiredMovements;
        private final Set<String> discouragedMovements;
        private final Integer recommendedReadCount;
        private final Integer recommendedWriteCount;
        private final Integer recommendedExitCount;

        private Profile(String label,
                        Set<String> requiredMovements,
                        Set<String> discouragedMovements,
                        Integer recommendedReadCount,
                        Integer recommendedWriteCount,
                        Integer recommendedExitCount) {
            this.label = label;
            this.requiredMovements = requiredMovements;
            this.discouragedMovements = discouragedMovements;
            this.recommendedReadCount = recommendedReadCount;
            this.recommendedWriteCount = recommendedWriteCount;
            this.recommendedExitCount = recommendedExitCount;
        }

        String getLabel() {
            return label;
        }

        Set<String> getRequiredMovements() {
            return requiredMovements;
        }

        Set<String> getDiscouragedMovements() {
            return discouragedMovements;
        }

        Integer getRecommendedReadCount() {
            return recommendedReadCount;
        }

        Integer getRecommendedWriteCount() {
            return recommendedWriteCount;
        }

        Integer getRecommendedExitCount() {
            return recommendedExitCount;
        }
    }
}
