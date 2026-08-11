package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.AiModelProfile;

import java.util.Locale;

/**
 * DeepSeek-specific execution protocol. COSMIC business rules are deliberately not
 * stored here: the only complete rule text is supplied by SplitRuleConfigService in
 * each task prompt.
 */
public final class DeepSeekPromptProfile {

    private DeepSeekPromptProfile() {
    }

    public static boolean matches(AiModelProfile profile) {
        return profile != null && matches(profile.getProvider(), profile.getBaseUrl(), profile.getModel());
    }

    public static boolean matches(String provider, String baseUrl, String model) {
        String providerValue = normalize(provider);
        String baseUrlValue = normalize(baseUrl);
        String modelValue = normalize(model);
        return providerValue.contains("deepseek")
                || baseUrlValue.contains("deepseek.com")
                || modelValue.startsWith("deepseek")
                || modelValue.contains("/deepseek");
    }

    public static String systemPrompt(AiPromptTask task) {
        String taskName;
        switch (task) {
            case EXTRACTION:
                taskName = "需求功能提取与 COSMIC 拆分";
                break;
            case INITIAL_REVIEW:
                taskName = "COSMIC 初审风险复核";
                break;
            case REUSE_ADJUSTMENT:
                taskName = "COSMIC 复用度全表调整";
                break;
            case DEEP_REVIEW:
                taskName = "COSMIC 深度审核";
                break;
            case FUNCTION_DESCRIPTION:
                taskName = "COSMIC 功能描述生成";
                break;
            default:
                taskName = "结构化业务分析";
                break;
        }
        String ruleConstraint = task == AiPromptTask.FUNCTION_DESCRIPTION
                ? "只根据用户消息中的现有功能过程和子过程生成业务化描述，不重新拆分、不改变 E/R/W/X、复用度或 CFP；"
                : "用户消息中标记的【当前生效完整规则】是唯一业务判定依据；";
        return "你是执行“" + taskName + "”的资深 COSMIC 专家。"
                + ruleConstraint
                + "DeepSeek 执行协议只规定执行顺序和输出格式，不新增、删除或改写业务规则。"
                + "请在内部完成充分推理和逐项自检，但最终 content 只能输出一个合法 JSON 对象，"
                + "不得输出推理过程、Markdown、注释或解释性前后缀。";
    }

    public static String specialize(AiPromptTask task, String taskPrompt, String ruleVersion) {
        StringBuilder builder = new StringBuilder();
        builder.append("【DeepSeek 专项执行协议】\n");
        builder.append("协议版本：deepseek-cosmic-v1\n");
        builder.append("唯一规则源：SplitRuleConfigService 当前生效完整规则，规则版本：")
                .append(text(ruleVersion)).append("。\n");
        builder.append("若执行协议、示例、输入数据与完整规则发生冲突，必须以完整规则为准；不得根据常识补规则。\n");
        builder.append("内部执行顺序：\n");
        builder.append("1. 先定位原文证据、历史评审批注和有效引用，证据不足不猜测。\n");
        builder.append("2. 再按完整规则判断功能边界、功能级重复与子过程级复用。\n");
        builder.append("3. 再逐行核对数据移动、数据组、数据属性、软件边界和 CFP/复用度。\n");
        builder.append("4. 最后执行完整性、引用有效性、去重和 JSON 字段自检。\n");
        builder.append("5. 只输出可由输入证据支持的最终结论，不输出内部分析过程。\n");
        appendTaskContract(builder, task);
        builder.append("【DeepSeek 专项执行协议结束】\n\n");
        builder.append(taskPrompt == null ? "" : taskPrompt.trim());
        builder.append("\n\n【DeepSeek 最终输出提醒】\n");
        builder.append(outputReminder(task));
        return builder.toString();
    }

    public static String emptyJson(AiPromptTask task) {
        switch (task) {
            case EXTRACTION:
                return "{\"functions\":[]}";
            case INITIAL_REVIEW:
                return "{\"items\":[]}";
            case REUSE_ADJUSTMENT:
                return "{\"decisions\":[]}";
            case DEEP_REVIEW:
                return "{\"suggestions\":[]}";
            case FUNCTION_DESCRIPTION:
                return "{}";
            default:
                return "{}";
        }
    }

    private static void appendTaskContract(StringBuilder builder, AiPromptTask task) {
        switch (task) {
            case EXTRACTION:
                builder.append("任务输出协议：顶层固定为 {\"functions\":[...]}；functions 每个元素严格使用任务正文定义的字段。\n");
                builder.append("顺序协议：先按功能自身 sourceReferences 对应的 REQ-L 原文顺序排列 functions；不得把原文前置的新增、定时生成或导入功能移到查询、修改、删除、导出、下载之后。\n");
                break;
            case INITIAL_REVIEW:
                builder.append("任务输出协议：顶层固定为 {\"items\":[...]}；items 每个元素严格使用任务正文定义的字段。\n");
                break;
            case REUSE_ADJUSTMENT:
                builder.append("任务输出协议：顶层固定为 {\"decisions\":[...]}；decisions 只包含确需改变复用度的行。\n");
                break;
            case DEEP_REVIEW:
                builder.append("任务输出协议：顶层固定为 {\"suggestions\":[...]}；suggestions 每个元素严格使用任务正文定义的字段。\n");
                break;
            case FUNCTION_DESCRIPTION:
                builder.append("任务输出协议：顶层为任务正文指定 key 到功能描述的 JSON 对象。\n");
                break;
            default:
                builder.append("任务输出协议：只输出任务正文要求的合法 JSON 对象。\n");
                break;
        }
    }

    private static String outputReminder(AiPromptTask task) {
        return "输出必须以“{”开始、以“}”结束；无结果时输出 " + emptyJson(task)
                + "；不要输出 ```json、分析、思考过程或额外字段。";
    }

    private static String normalize(String value) {
        return text(value).toLowerCase(Locale.ROOT);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
