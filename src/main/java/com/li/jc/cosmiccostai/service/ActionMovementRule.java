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
    // v2.8：修改默认 E-W-X，只有需求明示需读原记录时才加 R（不强制、不禁止）。
    private static final Profile MODIFY = profile(
            "修改", setOf("W", "X"), emptySet(), 0, 1, 1);
    private static final Profile DELETE = profile(
            "删除", setOf("W", "X"), setOf("R"), 0, 1, 1);
    // v2.8：导入 E-W-X（解析、校验不计 R）；用户导出 E-R-X（生成导出文件不计 W，规则 V4）。
    private static final Profile IMPORT = profile(
            "导入", setOf("W", "X"), setOf("R"), 0, 1, 1);
    private static final Profile EXPORT = profile(
            "导出", setOf("R", "X"), setOf("W"), 1, 0, 1);
    private static final Profile FILE_DOWNLOAD = profile(
            "下载/预览已有文件", setOf("R", "X"), emptySet(), 1, 0, 1);
    // v2.8：派发/审批 E-W-X，读取流程下一环节/处理人的 R 只在需求明示时写。
    private static final Profile WORKFLOW = profile(
            "流程派发/审批", setOf("W", "X"), emptySet(), 0, 1, 1);
    // v2.8：定时汇总只计定时 E 与结果 W，源表 R 严口径不计，不强制写。
    private static final Profile SCHEDULED_SUMMARY = profile(
            "定时汇总/统计", setOf("W"), emptySet(), 0, 1, 0);
    private static final Profile FILE_COLLECTION = profile(
            "定时文件采集", setOf("W"), emptySet(), 0, 1, 0);
    private static final Profile MESSAGE_DELIVERY = profile(
            "发送/推送", setOf("R", "X"), emptySet(), 1, null, 1);
    private static final Profile CALLED_QUERY_INTERFACE = profile(
            "被调用方查询接口", setOf("R", "X"), setOf("W"), 1, 0, 1);
    private static final Profile CALLED_SYNC_INTERFACE = profile(
            "被调用方同步接口", setOf("W", "X"), setOf("R"), 0, 1, 1);
    // v2.8：定时/系统触发的拉取，X 请求与 E 响应严口径不计，只计落库 W。
    private static final Profile CALLER_PULL_INTERFACE = profile(
            "调用方拉取同步", setOf("W"), emptySet(), 0, 1, 0);
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
        if (isGeneratedFileExport(process, context)) {
            addUnique(matches, EXPORT, firstKeyword(process, "导出", "下载", "预览"));
        } else if (containsAny(process, "下载", "预览")) {
            addUnique(matches, FILE_DOWNLOAD, firstKeyword(process, "下载", "预览"));
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
        return "统一动作组合（v2.8 三行骨架；AI 拆分、初审、深度审核、保存和导出使用同一口径）：\n"
                + "- 每个功能过程只写 首 E + 主体（W 或 R）+ 末 X 三行：查询 E-R-X；新增、修改、删除、状态变更 E-W-X；"
                + "导入 E-W-X；用户导出 E-R-X；无接收方的定时汇总 E-W。不写 E-R-W-X 四行、多 R、多 W。\n"
                + "- 修改不默认补“读取原记录”R；只有需求明示必须读取原记录时才写 R。新增/删除不补“读取相关数据”R。\n"
                + "- 导入：解析、校验不计 R；W 写入导入业务数据，X 返回导入成功/失败数量或错误明细，二者分开写。\n"
                + "- 用户触发导出：E 导出条件 → R 业务数据 → X 导出文件/下载地址；生成导出文件不计 W，"
                + "只有需求明示文件在服务器留存、可再次下载时才加 W。定时生成并留存、供下游推送的文件 W 计量。\n"
                + "- 下载或预览已有文件 E-R-X。\n"
                + "- 流程派发/审批：E 派发/审批意见 → W 派发/审批结果 → X 结果；需求明示待办/短信时末尾加 X 至 OA/短信平台。\n"
                + "- 定时统计/汇总：E 定时触发 → W 结果表，有具名接收方时加 X；源表 R 严口径不计，汇总计算不计。"
                + "指标、粒度、层级、日月表变体只写一个过程。\n"
                + "- 定时文件采集：E 定时触发 → W 保存文件/写入业务表，默认单过程；采集与入库是同一调度链。\n"
                + "- 生成并推送文件：E 定时触发 → W 生成业务文件 → X 推送对端 SFTP；内部“文件生成事件”不是新触发，不回读刚生成的文件。\n"
                + "- 发送/推送/短信通知：E-R-X；只有需求明示保存发送记录时才加 W。推送到其他系统的 SFTP/Kafka 一律 X。\n"
                + "- 被调用方：查询接口 E-R-X，接收同步数据 E-W-X；推送方已在本表拆分时，接收端整过程不计。\n"
                + "- 定时/系统触发的拉取：E 定时触发 → W 落库；向外部发送请求的 X 与接收返回的 E 严口径不计。"
                + "读取本系统数据并向外推送：E-R-X。\n"
                + "- 用户触发的实时外部调用：E 用户请求 → X 请求外部 → E 同步响应 → W 结果（真实落库）→ X 返回用户，"
                + "这是唯一允许超过 4 行的形态；串联第 2 个同类系统起按复用。\n"
                + "- 外部平台稍后以回调/上报接口触发本系统时属于新的独立触发，必须另拆功能过程；不得把异步回调伪装为同步响应 E。\n"
                + "- E 描述按真实发起方使用自然业务语言：用户主动触发写“发起/提交”，外部平台上报或回调才写“接收”，"
                + "X 后同步响应写“获取同步响应数据”；禁止机械拼接“接收+功能过程名+请求”。\n"
                + "- 一个 R/W 只对应一个逻辑持久化数据组；同一对象多字段只计一个 W。严口径同一过程只计第 1 个目标表 W。\n"
                + "- 顺序要求：首行必须是初始触发 E，末行为 W/X，至少 2 行；后续 E 只能是本系统发 X 请求后的同步响应 E，禁止连续 E。\n";
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

    private static boolean isGeneratedFileExport(String process, String context) {
        if (containsAny(process, "导出")) {
            return true;
        }
        if (!containsAny(process, "下载", "预览")) {
            return false;
        }
        String value = safe(context);
        boolean generated = containsAny(value, "生成", "创建", "写入", "导出");
        boolean file = containsAny(value, "Excel", "EXCEL", "excel", "CSV", "csv", "PDF", "pdf",
                "文件", "报表");
        return generated && file;
    }

    static boolean hasMessageRecordEvidence(String text) {
        String value = safe(text);
        return containsAny(value, "短信结果", "发送记录", "发送结果", "发送状态",
                "通知记录", "通知结果", "消息记录", "消息结果");
    }

    /** 导出文件在服务器留存、可再次下载的需求依据（v2.8 导出加 W 的唯一条件）。 */
    static boolean hasServerRetentionEvidence(String text) {
        String value = safe(text);
        if (containsAny(value, "下载后立即删除", "下载后删除", "临时文件", "临时生成")) {
            return false;
        }
        return containsAny(value, "留存", "存档", "存储到服务器", "保存到服务器", "服务器保存",
                "再次下载", "重复下载", "不删除");
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
