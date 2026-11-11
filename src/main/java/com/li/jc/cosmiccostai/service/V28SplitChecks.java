package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.RuleValidationIssue;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 规则 v2.8 的确定性初审检查（与 cosmic-cost-workbench 技能的 expr_checks / split_checks 同口径）。
 *
 * <ul>
 *   <li>表达层 V35–V38：只影响评估师是否误判，提示不拦截；</li>
 *   <li>拆分约束 V39–V44、V46–V48：AI 生成结果必须为 0，Excel 导入只提示；</li>
 *   <li>评审侧 V45：只提示；</li>
 *   <li>不成立的移动/过程 V2、V3、V13、V15、V21。</li>
 * </ul>
 * 每条问题按行输出，cfpImpact 为该行当前 CFP，同一编号求和即“涉及 CFP”。
 */
final class V28SplitChecks {

    static final String FIELD_SPLIT_CONSTRAINT = "拆分约束";
    static final String FIELD_EXPRESSION = "表达层";
    static final String FIELD_REVIEW_SIDE = "评审侧风险";
    static final String FIELD_INVALID_MOVEMENT = "不成立移动";

    /** 规则编号 → 标题，供汇总展示。 */
    static final Map<String, String> TITLES = new LinkedHashMap<String, String>();

    static {
        TITLES.put("V2", "回读本过程刚写入的数据");
        TITLES.put("V3", "查询/展示中的加工 W");
        TITLES.put("V4", "用户导出写“生成导出文件”W");
        TITLES.put("V10", "定时首 E 未按 1/3");
        TITLES.put("V13", "模板化补齐行");
        TITLES.put("V15", "调度基础设施过程");
        TITLES.put("V21", "优化/扩容类标题下按新增申报");
        TITLES.put("V31", "申报 CFP / 唯一事务数大于 3.5");
        TITLES.put("V34", "新建档占比偏高");
        TITLES.put("V35", "数据组为英文代号或占位");
        TITLES.put("V36", "状态词过程名或子过程非动词开头");
        TITLES.put("V37", "数据属性少于 2 个");
        TITLES.put("V38", "单个三级功能过程数大于 5");
        TITLES.put("V39", "超出三行骨架的中间 R/W");
        TITLES.put("V40", "过程内复用度混填");
        TITLES.put("V41", "内部事件/轮询触发");
        TITLES.put("V42", "过程名含 UI/步骤词");
        TITLES.put("V43", "粒度变体过程");
        TITLES.put("V44", "归一化后重名过程");
        TITLES.put("V45", "触发与过程语义不符");
        TITLES.put("V46", "模板链步骤过程");
        TITLES.put("V47", "功能用户占位");
        TITLES.put("V48", "三级功能 CRUD 全铺");
    }

    private static final Pattern UI_WORDS = Pattern.compile(
            "展示|显示|点击|回填|跳转|弹窗|按钮|开关|状态码|失败原因|进度|轮询|界面|卡片|分页|排序|重置|下拉|切换|入参|组装");
    private static final Pattern INTERNAL_TRIGGER = Pattern.compile(
            "完成后|就绪后|轮询|回传|回调|状态变更|生成事件|入库事件|事件触发|系统触发|自动触发|周期到达");
    private static final Pattern GRANULARITY = Pattern.compile(
            "\\d+\\s*分钟|分钟级|小时级|[（(]小时[)）]|天级|周级|月级|按天(?!气)|按周|按月");
    private static final Pattern HUMAN_TRIGGER = Pattern.compile("用户发起|用户触发|人员触发|人工触发|管理员触发");
    private static final Pattern BATCH_PROCESS = Pattern.compile("(采集|汇总|轻汇|同步|统计|定时)(入库|推送|接入|计算)?$");
    private static final Pattern TIMER_TRIGGER = Pattern.compile("定时触发|调度触发|每日|每天|每月");
    private static final Pattern VIEW_PROCESS = Pattern.compile("查询|展示|查看|呈现|详情");
    private static final Pattern TEMPLATE_STAGE = Pattern.compile(
            "(稽核|核查|分类|清除|去重|归档|回存归档|状况监测|情况监控|告警下发|预警分发|预警发送|异常处置|缺失变更|缺失数据更新|重报|历史查询|失败原因查看)$");
    private static final Pattern PLACEHOLDER_USER = Pattern.compile(
            "^(发起者[：:])?\\s*(功能用户|用户|使用者|操作者|操作人员)\\s*($|\\n)");
    private static final Pattern NAME_NORMALIZE = Pattern.compile(
            "\\d+\\s*分钟|小时|全疆|省级|地市级|区县级|网格级|地市|区县|网格|[\\s（）()]");
    private static final Pattern STATE_WORD_PROCESS = Pattern.compile("^(开启|关闭|启用|禁用|停用|启停|打开)");
    private static final Pattern VERB_START = Pattern.compile(
            "^(接收|读取|保存|写入|输出|更新|删除|查询|获取|生成|调用|发送|推送|校验|展示|返回|记录|下载|上传|同步|存储|解析|导出|移除|向"
                    + "|导入|计算|触发|比对|汇总|统计|执行|提交|创建|新增|修改|注册|绑定|解绑|激活|派发|转派|审核|标记|清理|归档|加载|渲染"
                    + "|呈现|显示|取消|回填|筛选|发起|请求|反馈|通知|下发|上报|分配|审批|驳回|撤回|发布|采集|确认|设置|配置|录入|填写|编辑)");
    private static final Pattern ENGLISH_CODE = Pattern.compile("[A-Za-z_]{4,}|\\.");
    /** 业务通用的格式、协议与系统简称，不视为英文代号（工具侧对 V35 的误报修正）。 */
    private static final Pattern ENGLISH_WHITELIST = Pattern.compile(
            "Excel|EXCEL|excel|SFTP|Kafka|KAFKA|Topic|TOPIC|Token|IPTV|EOMS|Word|WORD|HTTPS|HTTP|JSON|Json");
    private static final Pattern ATTRIBUTE_SPLIT = Pattern.compile("[、,，;；/ ]+");
    private static final Pattern LOG_WRITE = Pattern.compile("日志|调用记录|操作记录");
    private static final Pattern SCHEDULE_WORDS = Pattern.compile("定时|调度|时钟");
    private static final Pattern RELATED_DATA_READ = Pattern.compile("读取.{0,12}相关数据");
    private static final Pattern WRITE_ACTION_PROCESS = Pattern.compile("新增|新建|修改|编辑|删除|重命名|副本|复制|检测|执行|测试");
    private static final Pattern PROCESS_RESULT_WRITE = Pattern.compile("保存.{0,12}处理结果");
    private static final Pattern READ_ACTION_PROCESS = Pattern.compile("查询|查看|详情|导出|下载|重置|预览|展示");
    private static final Pattern PAGING_EXIT = Pattern.compile("分页|排序信息|页码");
    private static final Pattern GENERIC_RESULT_EXIT = Pattern.compile("返回.{0,12}处理结果");
    private static final Pattern RENDER_WRITE = Pattern.compile("整理为表格|转换为坐标|坐标序列|构建图表|图表数据集|格式化|组装.{0,6}(数据|结果)");
    private static final Pattern SCHEDULER_INFRA = Pattern.compile(
            "分布式锁|获取锁|释放锁|获取Token|获取token|获取认证令牌|认证Token|执行前准备|执行完成记录|超时处理|异常处理|统一调度");
    private static final Pattern OPTIMIZE_TITLE = Pattern.compile(
            "优化|升级|扩容|增强|更新|调整|治理|改造|字段|扩展|建模|填报|申请");

    private V28SplitChecks() {
    }

    static List<RuleValidationIssue> check(List<FunctionSplitRow> rows) {
        List<RuleValidationIssue> issues = new ArrayList<RuleValidationIssue>();
        if (rows == null || rows.isEmpty()) {
            return issues;
        }
        Map<String, List<Integer>> processes = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < rows.size(); i++) {
            FunctionSplitRow row = rows.get(i);
            String key = StringUtils.hasText(safe(row.getFunctionProcess())) ? FunctionProcessKey.of(row) : "__ROW_" + i;
            List<Integer> group = processes.get(key);
            if (group == null) {
                group = new ArrayList<Integer>();
                processes.put(key, group);
            }
            group.add(i);
        }

        checkRows(rows, issues);

        Map<String, Integer> normalizedNames = new HashMap<String, Integer>();
        Map<String, List<List<Integer>>> level3 = new LinkedHashMap<String, List<List<Integer>>>();
        for (Map.Entry<String, List<Integer>> entry : processes.entrySet()) {
            if (entry.getKey().startsWith("__ROW_")) {
                continue;
            }
            FunctionSplitRow head = rows.get(entry.getValue().get(0));
            String normalized = normalizeName(head.getFunctionProcess());
            Integer count = normalizedNames.get(normalized);
            normalizedNames.put(normalized, count == null ? 1 : count + 1);
            String levelKey = safe(head.getLevel1Module()) + "\u001f" + safe(head.getLevel2Module())
                    + "\u001f" + safe(head.getLevel3Module());
            List<List<Integer>> list = level3.get(levelKey);
            if (list == null) {
                list = new ArrayList<List<Integer>>();
                level3.put(levelKey, list);
            }
            list.add(entry.getValue());
        }

        for (Map.Entry<String, List<Integer>> entry : processes.entrySet()) {
            if (entry.getKey().startsWith("__ROW_")) {
                continue;
            }
            checkProcess(rows, entry.getValue(), normalizedNames, issues);
        }
        for (List<List<Integer>> group : level3.values()) {
            checkLevel3(rows, group, issues);
        }
        return issues;
    }

    // ---------------- 行级：表达层 V35–V37、模板化补齐行 V13 ----------------

    private static void checkRows(List<FunctionSplitRow> rows, List<RuleValidationIssue> issues) {
        for (int i = 0; i < rows.size(); i++) {
            FunctionSplitRow row = rows.get(i);
            String group = safe(row.getDataGroup());
            String attributes = safe(row.getDataAttributes());
            String description = safe(row.getSubProcessDescription());
            String process = safe(row.getFunctionProcess());
            String type = movement(row);

            String groupCode = ENGLISH_WHITELIST.matcher(group).replaceAll("");
            if (!StringUtils.hasText(group) || "无".equals(group) || "无业务数据".equals(group) || "待确认".equals(group)
                    || ENGLISH_CODE.matcher(groupCode).find()) {
                add(issues, rows, i, "V35", FIELD_EXPRESSION,
                        "数据组“" + cut(group, 30) + "”是英文代号/表名或占位，评估师判不出数据对象会判 N",
                        "改为需求原文中的中文业务名（如“小区15分钟性能指标”）；取不到的列入待核实，不要编造。");
            }
            if (StringUtils.hasText(description) && !VERB_START.matcher(description).find()) {
                add(issues, rows, i, "V36", FIELD_EXPRESSION,
                        "子过程描述“" + cut(description, 30) + "”不以动词开头",
                        "改为“动词+名词”，以接收/读取/保存/输出/推送等动词开头，不写“系统返回…/系统按条件…”这类主语句。");
            }
            if (countAttributes(attributes) < 2) {
                add(issues, rows, i, "V37", FIELD_EXPRESSION,
                        "数据属性“" + cut(attributes, 30) + "”少于 2 个字段，无法证明是对一个数据组的移动",
                        "从需求原文补齐至少 2 个字段；补不出的列入待核实，不编造。");
            }

            String templateReason = null;
            if ("R".equals(type) && RELATED_DATA_READ.matcher(description).find()
                    && WRITE_ACTION_PROCESS.matcher(process).find()) {
                templateReason = "新增/修改/删除/执行类过程中的“读取XX相关数据”R";
            } else if ("W".equals(type) && PROCESS_RESULT_WRITE.matcher(description).find()
                    && READ_ACTION_PROCESS.matcher(process).find()) {
                templateReason = "查询/查看/导出类过程中的“保存XX处理结果”W";
            } else if ("X".equals(type) && PAGING_EXIT.matcher(description + group).find()) {
                templateReason = "“输出分页和排序信息”X（与列表 X 重复）";
            } else if ("X".equals(type) && GENERIC_RESULT_EXIT.matcher(description).find()
                    && (group.contains("处理结果") || !StringUtils.hasText(group))) {
                templateReason = "没有具体数据组的“返回XX处理结果”X";
            }
            if (templateReason != null) {
                add(issues, rows, i, "V13", FIELD_INVALID_MOVEMENT,
                        templateReason + "是模板化补齐行，评审判 N",
                        "删除该行；如确有真实数据移动，写清具体数据组与需求原文字段。");
            }
        }
    }

    // ---------------- 过程级：V2、V3、V15、V21、V39–V45、V47 ----------------

    private static void checkProcess(List<FunctionSplitRow> rows, List<Integer> indexes,
                                     Map<String, Integer> normalizedNames, List<RuleValidationIssue> issues) {
        FunctionSplitRow head = rows.get(indexes.get(0));
        String process = safe(head.getFunctionProcess());
        String trigger = safe(head.getTriggerEvent());
        String user = safe(head.getFunctionalUser());
        StringBuilder skeleton = new StringBuilder();
        for (Integer index : indexes) {
            skeleton.append(movement(rows.get(index)));
        }
        String types = skeleton.toString();
        int reads = count(types, 'R');
        int writes = count(types, 'W');
        int entries = count(types, 'E');

        // V39 三行骨架
        if (reads + writes >= 2 || (indexes.size() > 4 && entries < 2)) {
            List<Integer> extra = nonSkeletonRows(rows, indexes);
            addEach(issues, rows, extra.isEmpty() ? indexes : extra, "V39", FIELD_SPLIT_CONSTRAINT,
                    "骨架 " + types + " 超出“首 E + 主体 + 末 X”三行，多出的中间 R/W 会被判 N",
                    "只保留首 E、主体（第 1 个 W，没有 W 则第 1 个 R）、末 X；删除读取参照/配置/上期数据/维表和写中间表、"
                            + "第 2 个目标表的行。用户触发的外部调用链和需求明示的第二接收方 X 除外。");
        }

        // V40 过程内复用度混填（忽略定时首 E 与日志 W）
        Set<String> body = new LinkedHashSet<String>();
        List<String> all = new ArrayList<String>();
        for (int k = 0; k < indexes.size(); k++) {
            FunctionSplitRow row = rows.get(indexes.get(k));
            String reuse = safe(row.getReuseDegree());
            all.add(reuse);
            boolean timerEntry = k == 0 && isScheduled(trigger);
            boolean logWrite = "W".equals(movement(row)) && LOG_WRITE.matcher(safe(row.getSubProcessDescription())).find();
            if (!timerEntry && !logWrite) {
                body.add(reuse);
            }
        }
        if (body.size() > 1) {
            addEach(issues, rows, indexes, "V40", FIELD_SPLIT_CONSTRAINT,
                    "过程内复用度混填（" + String.join("/", all) + "），评审会按过程定档抹平",
                    "先按事务三档给过程定档，再整过程统一填新增或复用；只有定时首 E 和日志 W 可以单独为复用。");
        }

        // V41 内部事件触发（外部系统通过接口回调/上报属于独立触发，不算）
        boolean externalCallback = trigger.contains("接口") || trigger.contains("调用本系统");
        if (INTERNAL_TRIGGER.matcher(trigger).find() && !externalCallback) {
            addEach(issues, rows, indexes, "V41", FIELD_SPLIT_CONSTRAINT,
                    "触发事件“" + cut(trigger, 24) + "”是本系统内部事件/轮询，评审判内部事件二段过程 0",
                    "并入上游过程，触发事件改为“{功能用户}触发{过程名}”或真实调度时间。");
        }
        // V36 过程名以状态词开头（只记在过程首行）
        if (STATE_WORD_PROCESS.matcher(process).find()) {
            add(issues, rows, indexes.get(0), "V36", FIELD_EXPRESSION,
                    "过程名“" + cut(process, 24) + "”以状态词开头，会被理解为状态切换、无数据移动",
                    "写成“XX启用状态修改”“XX关闭指令下发”，并让 W/X 行可见。");
        }
        // V42 UI/步骤词
        if (UI_WORDS.matcher(process).find()) {
            addEach(issues, rows, indexes, "V42", FIELD_SPLIT_CONSTRAINT,
                    "过程名“" + cut(process, 24) + "”含 UI/步骤词，评估师见到即判页面交互 0",
                    "改为“业务对象+操作”名词短语，如“机顶盒设备信息稽核”“预警对象查询”。");
        }
        // V43 粒度变体
        if (GRANULARITY.matcher(process).find()) {
            addEach(issues, rows, indexes, "V43", FIELD_SPLIT_CONSTRAINT,
                    "过程名“" + cut(process, 24) + "”含分钟/小时/天/周/月粒度，严口径变体整过程 0",
                    "同一指标的各粒度只写一个过程，粒度差异写进数据组和属性。");
        }
        // V44 归一化后重名
        Integer sameName = normalizedNames.get(normalizeName(process));
        if (sameName != null && sameName > 1) {
            addEach(issues, rows, indexes, "V44", FIELD_SPLIT_CONSTRAINT,
                    "过程名归一化后“" + cut(normalizeName(process), 24) + "”出现 " + sameName + " 次，会被判“功能同 xx-yy”",
                    "同名或同义过程只保留一个，其余删除或引用首个；不同场景按“对象×阶段”命名区分。");
        }
        // V45 触发与语义不符（只提示）
        if ((HUMAN_TRIGGER.matcher(trigger).find() && BATCH_PROCESS.matcher(process).find())
                || (TIMER_TRIGGER.matcher(trigger).find() && VIEW_PROCESS.matcher(process).find()
                && !BATCH_PROCESS.matcher(process).find())) {
            addEach(issues, rows, indexes, "V45", FIELD_REVIEW_SIDE,
                    "触发“" + cut(trigger, 20) + "”与过程“" + cut(process, 20) + "”语义不符，评估师可据此质疑功能用户真实性",
                    "采集、汇总、同步、统计写定时或接口触发；查询、展示写用户触发。");
        }
        // V47 功能用户占位
        if (PLACEHOLDER_USER.matcher(user).find()) {
            addEach(issues, rows, indexes, "V47", FIELD_SPLIT_CONSTRAINT,
                    "功能用户“" + cut(user, 12) + "”是占位写法",
                    "写真实角色或系统：装维人员、代维调度员、业务编排系统、集团 EOMS、调度任务。");
        }
        // V15 调度基础设施
        if (SCHEDULER_INFRA.matcher(process).find()) {
            addEach(issues, rows, indexes, "V15", FIELD_INVALID_MOVEMENT,
                    "过程“" + cut(process, 24) + "”属于调度基础设施（锁/Token/准备/完成/超时/统一调度），评审整过程 N",
                    "删除该过程，只保留各业务定时任务本身。");
        }
        // V21 优化/扩容类标题下按新增申报
        String modules = safe(head.getLevel1Module()) + " " + safe(head.getLevel2Module()) + " " + safe(head.getLevel3Module());
        if (OPTIMIZE_TITLE.matcher(modules).find()) {
            List<Integer> fresh = new ArrayList<Integer>();
            for (Integer index : indexes) {
                if ("新增".equals(safe(rows.get(index).getReuseDegree()))) {
                    fresh.add(index);
                }
            }
            if (!fresh.isEmpty()) {
                addEach(issues, rows, fresh, "V21", FIELD_REVIEW_SIDE,
                        "模块名含“优化/升级/扩容/改造/调整/字段”等，严口径整段按已有能力改造 O 计 1/3，当前按新增申报",
                        "改造类过程按复用申报；真正新建的能力挪到独立“新增XX”标题下，并在需求说明书写现状对照。");
            }
        }

        // V2 回读本过程刚写入的数据组
        Set<String> written = new LinkedHashSet<String>();
        for (Integer index : indexes) {
            FunctionSplitRow row = rows.get(index);
            String group = safe(row.getDataGroup());
            if ("W".equals(movement(row)) && StringUtils.hasText(group)) {
                written.add(group);
            } else if ("R".equals(movement(row)) && written.contains(group)) {
                add(issues, rows, index, "V2", FIELD_INVALID_MOVEMENT,
                        "回读本过程刚写入的数据组“" + cut(group, 20) + "”，评审判 N",
                        "删除该 R；同一过程内写入后的数据直接用于后续输出，不再单独读取。");
            }
        }
        // V3 查询/展示中的加工 W
        if (VIEW_PROCESS.matcher(process).find() && !BATCH_PROCESS.matcher(process).find()) {
            for (Integer index : indexes) {
                FunctionSplitRow row = rows.get(index);
                if ("W".equals(movement(row)) && RENDER_WRITE.matcher(safe(row.getSubProcessDescription())).find()) {
                    add(issues, rows, index, "V3", FIELD_INVALID_MOVEMENT,
                            "查询/展示过程中“整理为表格/构建图表数据/格式化”的 W，评审判 N",
                            "删除该 W；查询按 E-R-X，表格化、图表加工是内部运算。");
                }
            }
        }
    }

    // ---------------- 三级功能级：V38、V46、V48 ----------------

    private static void checkLevel3(List<FunctionSplitRow> rows, List<List<Integer>> processes,
                                    List<RuleValidationIssue> issues) {
        FunctionSplitRow first = rows.get(processes.get(0).get(0));
        String level3 = safe(first.getLevel3Module());
        if (processes.size() > 5) {
            addProcessHeads(issues, rows, processes, "V38", FIELD_EXPRESSION,
                    "三级功能“" + cut(level3, 16) + "”有 " + processes.size() + " 个过程（建议 2–5 个），过多会被互相复用",
                    "按业务对象拆成多个三级（只改三级模块归属，不增删过程）。");
        }
        int stages = 0;
        StringBuilder names = new StringBuilder();
        for (List<Integer> process : processes) {
            String name = safe(rows.get(process.get(0)).getFunctionProcess());
            names.append(name).append(' ');
            if (TEMPLATE_STAGE.matcher(name).find()) {
                stages++;
            }
        }
        if (stages >= 3) {
            List<List<Integer>> stageProcesses = new ArrayList<List<Integer>>();
            for (List<Integer> process : processes) {
                if (TEMPLATE_STAGE.matcher(safe(rows.get(process.get(0)).getFunctionProcess())).find()) {
                    stageProcesses.add(process);
                }
            }
            addProcessHeads(issues, rows, stageProcesses, "V46", FIELD_SPLIT_CONSTRAINT,
                    "三级功能“" + cut(level3, 16) + "”含 " + stages + " 个模板链步骤过程（稽核/分类/清除/归档/监测/告警/处置/重报…），"
                            + "严口径整段 0 且拖累链首",
                    "整段删除，只保留链首一个过程（如“XX数据定时汇总入库”）。");
        }
        String ops = names.toString();
        if (processes.size() >= 7 && ops.contains("查询") && ops.contains("新增") && ops.contains("修改")
                && ops.contains("删除") && ops.contains("导出") && (ops.contains("导入") || ops.contains("详情"))) {
            addProcessHeads(issues, rows, processes, "V48", FIELD_SPLIT_CONSTRAINT,
                    "三级功能“" + cut(level3, 16) + "” " + processes.size() + " 个过程增删改查导入导出全铺，"
                            + "存量对象或查询类对象铺满时评审整体 O 或 0",
                    "收成需求有依据的操作；存量对象整体按复用，查询类对象只写查询+详情+导出。");
        }
    }

    // ---------------- 工具方法 ----------------

    /** 三行骨架之外的行：首 E、主体（第 1 个 W，否则第 1 个 R）、末 X（否则最后一个 W）以外的行。 */
    static List<Integer> nonSkeletonRows(List<FunctionSplitRow> rows, List<Integer> indexes) {
        Set<Integer> keep = skeletonPositions(rows, indexes);
        List<Integer> extra = new ArrayList<Integer>();
        for (int k = 0; k < indexes.size(); k++) {
            if (!keep.contains(k)) {
                extra.add(indexes.get(k));
            }
        }
        return extra;
    }

    /** 骨架行在过程内的位置（0 起）：首行、主体、末 X。 */
    static Set<Integer> skeletonPositions(List<FunctionSplitRow> rows, List<Integer> indexes) {
        Set<Integer> keep = new LinkedHashSet<Integer>();
        keep.add(0);
        int firstWrite = -1;
        int firstRead = -1;
        int lastExit = -1;
        int lastWrite = -1;
        for (int k = 0; k < indexes.size(); k++) {
            String type = movement(rows.get(indexes.get(k)));
            if ("W".equals(type)) {
                if (firstWrite < 0) {
                    firstWrite = k;
                }
                lastWrite = k;
            } else if ("R".equals(type) && firstRead < 0) {
                firstRead = k;
            } else if ("X".equals(type)) {
                lastExit = k;
            }
        }
        if (firstWrite >= 0) {
            keep.add(firstWrite);
        } else if (firstRead >= 0) {
            keep.add(firstRead);
        }
        if (lastExit >= 0) {
            keep.add(lastExit);
        } else if (lastWrite >= 0) {
            keep.add(lastWrite);
        }
        return keep;
    }

    static String normalizeName(String value) {
        return NAME_NORMALIZE.matcher(safe(value)).replaceAll("");
    }

    /** 去掉层级与粒度后的过程名，用于识别加工链变体（v2.8 第 3.2 节第 2 步）。 */
    static String normalizeVariant(String value) {
        return GRANULARITY.matcher(normalizeName(value)).replaceAll("");
    }

    /** 日志/调用记录/操作记录 W：v2.8 允许单独按 1/3，不参与过程级复用度统一。 */
    static boolean isLogWrite(FunctionSplitRow row) {
        return "W".equals(movement(row)) && LOG_WRITE.matcher(safe(row.getSubProcessDescription())).find();
    }

    static boolean isUiProcess(String process) {
        return UI_WORDS.matcher(safe(process)).find();
    }

    static boolean isInternalTrigger(String trigger) {
        String value = safe(trigger);
        return INTERNAL_TRIGGER.matcher(value).find() && !(value.contains("接口") || value.contains("调用本系统"));
    }

    static boolean isTemplateStage(String process) {
        return TEMPLATE_STAGE.matcher(safe(process)).find();
    }

    static boolean isSchedulerInfrastructure(String process) {
        return SCHEDULER_INFRA.matcher(safe(process)).find();
    }

    static boolean isOptimizeTitle(String modules) {
        return OPTIMIZE_TITLE.matcher(safe(modules)).find();
    }

    static boolean isScheduled(String trigger) {
        String value = safe(trigger).replace("调度员", "");
        return SCHEDULE_WORDS.matcher(value).find() || TIMER_TRIGGER.matcher(value).find();
    }

    private static int countAttributes(String attributes) {
        int count = 0;
        for (String part : ATTRIBUTE_SPLIT.split(safe(attributes))) {
            if (StringUtils.hasText(part)) {
                count++;
            }
        }
        return count;
    }

    private static void addProcessHeads(List<RuleValidationIssue> issues, List<FunctionSplitRow> rows,
                                        List<List<Integer>> processes, String code, String field,
                                        String message, String suggestion) {
        for (List<Integer> process : processes) {
            addEach(issues, rows, process, code, field, message, suggestion);
        }
    }

    private static void addEach(List<RuleValidationIssue> issues, List<FunctionSplitRow> rows, List<Integer> indexes,
                                String code, String field, String message, String suggestion) {
        for (Integer index : indexes) {
            add(issues, rows, index, code, field, message, suggestion);
        }
    }

    private static void add(List<RuleValidationIssue> issues, List<FunctionSplitRow> rows, int index,
                            String code, String field, String message, String suggestion) {
        FunctionSplitRow row = rows.get(index);
        RuleValidationIssue issue = new RuleValidationIssue();
        issue.setRowIndex(index + 1);
        issue.setFunctionProcess(safe(row.getFunctionProcess()));
        issue.setField(field);
        issue.setSeverity("RISK");
        issue.setRuleCode(code);
        issue.setMessage(code + " " + message);
        issue.setSuggestion(suggestion);
        issue.setCfpImpact(row.getCfp());
        issues.add(issue);
    }

    private static String movement(FunctionSplitRow row) {
        return safe(row.getMovementType()).toUpperCase(Locale.ROOT);
    }

    private static int count(String value, char target) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    private static String cut(String value, int length) {
        String text = safe(value).replace('\n', ' ');
        return text.length() <= length ? text : text.substring(0, length);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
