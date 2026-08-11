package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.AiReviewValidationItem;
import com.li.jc.cosmiccostai.dto.AiReviewValidationRequest;
import com.li.jc.cosmiccostai.dto.AiReviewValidationResponse;
import com.li.jc.cosmiccostai.dto.AiReviewValidationRow;
import com.li.jc.cosmiccostai.dto.AiModelProfile;
import com.li.jc.cosmiccostai.dto.RuleValidationIssue;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class AiReviewValidationService {

    private static final Logger log = LoggerFactory.getLogger(AiReviewValidationService.class);
    private static final int MAX_ROWS = 20;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final long timeoutSeconds;
    private final int maxRetries;
    private final AiModelConfigService aiModelConfigService;
    private final SplitRuleConfigService splitRuleConfigService;

    @Autowired
    public AiReviewValidationService(@Value("${cosmic.ai.base-url:}") String baseUrl,
                                     @Value("${cosmic.ai.api-key:}") String apiKey,
                                     @Value("${cosmic.ai.model:gpt-4o-mini}") String model,
                                     @Value("${cosmic.ai.timeout-seconds:180}") long timeoutSeconds,
                                     @Value("${cosmic.ai.max-retries:2}") int maxRetries,
                                     AiModelConfigService aiModelConfigService,
                                     SplitRuleConfigService splitRuleConfigService) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
        this.aiModelConfigService = aiModelConfigService;
        this.splitRuleConfigService = splitRuleConfigService;
    }

    public AiReviewValidationResponse validate(AiReviewValidationRequest request) {
        List<AiReviewValidationRow> rows = request == null || request.getRows() == null
                ? Collections.<AiReviewValidationRow>emptyList()
                : request.getRows();
        if (rows.isEmpty()) {
            throw new BusinessException("\u8bf7\u5148\u9009\u62e9\u9700\u8981 AI \u590d\u6838\u7684\u98ce\u9669\u884c\u3002");
        }
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException("\u5355\u6b21 AI \u590d\u6838\u6700\u591a\u652f\u6301 " + MAX_ROWS + " \u884c\uff0c\u8bf7\u7f29\u5c0f\u9009\u62e9\u8303\u56f4\u540e\u91cd\u8bd5\u3002");
        }
        String prompt = buildPrompt(rows);
        String aiText = callAi(prompt);
        List<AiReviewValidationItem> items = parseItems(aiText);
        normalizeReuseSuggestions(items);
        AiReviewValidationResponse response = new AiReviewValidationResponse();
        response.setRequestedCount(rows.size());
        response.setModel(currentModel());
        response.setItems(items);
        return response;
    }

    private void normalizeReuseSuggestions(List<AiReviewValidationItem> items) {
        for (AiReviewValidationItem item : items == null
                ? Collections.<AiReviewValidationItem>emptyList() : items) {
            String reuse = text(item.getSuggestedReuseDegree());
            if (!"CONFIRMED_RISK".equals(text(item.getVerdict()))
                    || !("新增".equals(reuse) || "复用".equals(reuse) || "利旧".equals(reuse))) {
                item.setSuggestedReuseDegree(null);
                item.setSuggestedCfp(null);
                continue;
            }
            item.setSuggestedReuseDegree(reuse);
            item.setSuggestedCfp("新增".equals(reuse) ? 1D : ("复用".equals(reuse) ? 0.33D : 0D));
        }
    }

    private String buildPrompt(List<AiReviewValidationRow> rows) {
        boolean deepSeekPrompt = usesDeepSeekPrompt();
        StringBuilder builder = new StringBuilder();
        builder.append("\u4f60\u662f COSMIC \u529f\u80fd\u70b9\u62c6\u5206\u4e13\u5bb6\uff0c\u8bf7\u590d\u6838\u5df2\u901a\u8fc7\u7cfb\u7edf\u89c4\u5219\u521d\u7b5b\u7684\u9ad8\u98ce\u9669\u884c\u3002\n");
        builder.append("\u76ee\u6807\uff1a\u5224\u65ad\u8fd9\u4e9b\u884c\u662f\u5426\u771f\u7684\u4e0d\u5229\u4e8e COSMIC \u8ba1\u91cf\uff0c\u5e76\u7ed9\u51fa\u4eba\u5de5\u53ef\u91c7\u7eb3\u7684\u4fee\u6539\u5efa\u8bae\u3002\n");
        builder.append("\u8981\u6c42\uff1a\n");
        builder.append("1. \u4e0d\u8981\u81ea\u52a8\u6539\u6570\u636e\uff0c\u53ea\u8f93\u51fa\u590d\u6838\u610f\u89c1\u548c\u5efa\u8bae\u8868\u8fbe\u3002\n");
        builder.append("2. \u4f18\u5148\u68c0\u67e5\u529f\u80fd\u8fc7\u7a0b\u662f\u5426\u53ef\u8ba1\u91cf\uff0c\u662f\u5426\u660e\u786e\u4f53\u73b0\u6570\u636e\u79fb\u52a8\u3002\n");
        builder.append("3. \u68c0\u67e5\u5b50\u8fc7\u7a0b\u63cf\u8ff0\u3001\u6570\u636e\u7ec4\u3001\u6570\u636e\u5c5e\u6027\u662f\u5426\u5339\u914d E/R/W/X \u7c7b\u578b\u3002\n");
        builder.append("4. 必须结合 reviewContextRows 判断，里面同时包含同一功能过程的完整子过程和初审风险引用的跨功能对比行，不能只看 selectedRow。\n");
        builder.append("5. 如果同一功能过程上下文中已经存在 W 或 X，不要判断为缺少输出或写入；跨功能复用风险必须比较引用行的数据组、业务目标、边界和三要素。\n");
        builder.append("6. verdict \u53ea\u80fd\u662f CONFIRMED_RISK\u3001FALSE_POSITIVE\u3001NEED_REVIEW \u4e4b\u4e00\u3002\n");
        builder.append("7. suggestion \u5fc5\u987b\u6709\u503c\uff1aCONFIRMED_RISK \u5fc5\u987b\u7ed9\u51fa\u53ef\u91c7\u7eb3\u7684\u4fee\u6539\u5efa\u8bae\uff1bFALSE_POSITIVE \u8bf7\u5199\u660e\u201c\u65e0\u9700\u4fee\u6539\u201d\u548c\u5224\u65ad\u539f\u56e0\uff1bNEED_REVIEW \u8bf7\u5199\u660e\u9700\u4eba\u5de5\u786e\u8ba4\u7684\u70b9\u3002\n");
        builder.append("8. CONFIRMED_RISK 时，尽量填写可直接采纳的建议字段；涉及复用与 CFP 时必须填写 suggestedReuseDegree 和 suggestedCfp。\n");
        builder.append("8.1 \u7ed3\u6784\u53e3\u5f84\uff1a\u529f\u80fd\u8fc7\u7a0b\u81f3\u5c11 2 \u884c\uff0c\u9996\u884c\u662f\u521d\u59cb\u89e6\u53d1 E\uff0c\u672b\u884c\u5fc5\u987b\u662f W/X\uff1b\u540e\u7eed E \u53ea\u80fd\u662f\u672c\u7cfb\u7edf\u53d1\u51fa\u5916\u90e8\u8bf7\u6c42 X \u540e\u7684\u540c\u6b65\u54cd\u5e94\uff0c\u7981\u6b62\u65e0\u4f9d\u636e\u8fde\u7eed E\uff1b\u4e0d\u8bbe\u6700\u591a 4 \u884c\u9650\u5236\u3002\n");
        builder.append("8.2 \u7eaf\u524d\u7aef\u7ffb\u9875\u3001\u52fe\u9009\u3001\u56fe\u5c42\u5207\u6362\u3001\u81ea\u52a8\u586b\u5145\u3001\u9ad8\u4eae\u6e32\u67d3\u4e0d\u5355\u72ec\u8ba1\u91cf\uff1b\u6821\u9a8c\u3001\u8ba1\u7b97\u3001\u89e3\u6790\u7b49\u6570\u636e\u64cd\u4f5c\u4e0d\u5355\u72ec\u8ba1\u6570\u636e\u79fb\u52a8\u3002\n");
        builder.append("8.3 \u6570\u636e\u5c5e\u6027\u5e94\u5148\u68c0\u7d22\u539f\u6587\u8868\u683c\u5b57\u6bb5\u3001\u63a5\u53e3/\u62a5\u6587\u53c2\u6570\u3001\u6b63\u6587\u660e\u786e\u4e1a\u52a1\u6570\u636e\u9879\u548c\u540c\u4e00\u529f\u80fd\u8fc7\u7a0b\u4e0a\u4e0b\u6587\u3002\u76f8\u540c\u6570\u636e\u7ec4\u4f20\u9012\u540c\u4e00\u6279\u6570\u636e\u65f6\u590d\u7528\u5df2\u77e5\u5c5e\u6027\uff1b\u65e0\u8f7d\u8377\u5b9a\u65f6\u89e6\u53d1\u586b\u201c\u65e0\u4e1a\u52a1\u6570\u636e\u201d\u3002\u53ea\u6709\u4ecd\u65e0\u6cd5\u8bc6\u522b\u4efb\u4f55\u5c5e\u6027\u65f6\u624d\u6574\u683c\u586b\u201c\u5f85\u786e\u8ba4\u201d\uff0c\u7981\u6b62\u201c\u5b57\u6bb5\u540d\uff08\u5f85\u786e\u8ba4\uff09\u201d\u6df7\u5199\u3001\u865a\u6784\u5b57\u6bb5\u6216\u5199\u201c\u540c\u4e0a/\u89c1\u4e0a\u201d\u3002\n");
        builder.append("8.4 复用度与 CFP 映射为新增=1、复用=0.33、利旧=0。必须根据 reviewContextRows 做语义查重：完整用户目标重复/包含才建议利旧=0；用户目标不同但当前数据移动复用才建议复用=0.33；只有两项相同且证据不足时保持 NEED_REVIEW，不得为了消除风险强制改复用。\n");
        builder.append("8.5 \u5386\u53f2\u8bc4\u5ba1 N/NM/\u6570\u5b57\u884c\u53f7/O/OLD/UIUE/C/NR/\u7b97\u6cd5/\u9700\u6c42\u6587\u6863\u4e0d\u6d89\u53ca\u4f18\u5148\u4e8e\u901a\u7528\u6a21\u677f\uff1b\u4e0d\u5f97\u4e3a\u51d1\u7ed3\u6784\u8865\u201c\u8bfb\u53d6\u76f8\u5173\u6570\u636e/\u4fdd\u5b58\u5904\u7406\u7ed3\u679c\u201d\u6216\u6cdb\u5316\u5c5e\u6027\u3002\n");
        builder.append("8.6 E 类子过程按真实发起方自然表达：用户/UI 主动操作使用“发起/提交”，外部平台上报或回调才使用“接收”，X 后同步响应使用“获取同步响应数据”；不得机械拼接“接收+功能过程名+请求”。\n");
        builder.append(splitRuleConfigService.getEffectiveRulesPrompt());
        builder.append(deepSeekPrompt
                ? "9. 只输出顶层为 items 数组的 JSON 对象，不要 markdown、不要解释性前后缀；没有结果时输出 "
                        + DeepSeekPromptProfile.emptyJson(AiPromptTask.INITIAL_REVIEW) + "。\n"
                : "9. \u53ea\u8f93\u51fa JSON \u6570\u7ec4\uff0c\u4e0d\u8981 markdown\u3001\u4e0d\u8981\u89e3\u91ca\u6027\u524d\u540e\u7f00\u3002\n");
        builder.append("输出字段：rowIndex,functionProcess,verdict,reason,suggestion,suggestedFunctionProcess,suggestedSubProcessDescription,suggestedDataGroup,suggestedDataAttributes,suggestedReuseDegree,suggestedCfp,confidence。\n\n");
        builder.append("\u5f85\u590d\u6838\u884c\uff1a\n");
        for (AiReviewValidationRow item : rows) {
            FunctionSplitRow row = item.getRow();
            builder.append("- rowIndex: ").append(item.getRowIndex()).append("\n");
            builder.append("  selectedRow:\n");
            builder.append("  level1Module: ").append(text(row == null ? null : row.getLevel1Module())).append("\n");
            builder.append("  level2Module: ").append(text(row == null ? null : row.getLevel2Module())).append("\n");
            builder.append("  level3Module: ").append(text(row == null ? null : row.getLevel3Module())).append("\n");
            builder.append("  functionProcess: ").append(text(row == null ? null : row.getFunctionProcess())).append("\n");
            builder.append("  functionDescription: ").append(text(row == null ? null : row.getFunctionDescription())).append("\n");
            builder.append("  subProcessDescription: ").append(text(row == null ? null : row.getSubProcessDescription())).append("\n");
            builder.append("  movementType: ").append(text(row == null ? null : row.getMovementType())).append("\n");
            builder.append("  dataGroup: ").append(text(row == null ? null : row.getDataGroup())).append("\n");
            builder.append("  dataAttributes: ").append(text(row == null ? null : row.getDataAttributes())).append("\n");
            builder.append("  evidence: ").append(text(row == null ? null : row.getEvidence())).append("\n");
            builder.append("  sourceReferences: ").append(row == null || row.getSourceReferences() == null
                    ? "[]" : row.getSourceReferences()).append("\n");
            builder.append("  reuseDegree: ").append(text(row == null ? null : row.getReuseDegree())).append("\n");
            builder.append("  ruleIssues: ");
            appendIssues(builder, item.getIssues());
            builder.append("\n");
            builder.append("  reviewContextRows:\n");
            appendContextRows(builder, item.getContextRows());
        }
        String prompt = builder.toString();
        return deepSeekPrompt
                ? DeepSeekPromptProfile.specialize(
                        AiPromptTask.INITIAL_REVIEW, prompt, splitRuleConfigService.getRuleVersion())
                : prompt;
    }

    private void appendContextRows(StringBuilder builder, List<AiReviewValidationRow> contextRows) {
        if (contextRows == null || contextRows.isEmpty()) {
            builder.append("    \u65e0\n");
            return;
        }
        for (AiReviewValidationRow context : contextRows) {
            FunctionSplitRow row = context.getRow();
            builder.append("    - rowIndex: ").append(context.getRowIndex()).append("\n");
            builder.append("      movementType: ").append(text(row == null ? null : row.getMovementType())).append("\n");
            builder.append("      functionProcess: ").append(text(row == null ? null : row.getFunctionProcess())).append("\n");
            builder.append("      subProcessDescription: ").append(text(row == null ? null : row.getSubProcessDescription())).append("\n");
            builder.append("      dataGroup: ").append(text(row == null ? null : row.getDataGroup())).append("\n");
            builder.append("      dataAttributes: ").append(text(row == null ? null : row.getDataAttributes())).append("\n");
            builder.append("      sequenceDiagram: ").append(text(row == null ? null : row.getSequenceDiagram())).append("\n");
            builder.append("      evidence: ").append(text(row == null ? null : row.getEvidence())).append("\n");
            builder.append("      sourceReferences: ").append(row == null || row.getSourceReferences() == null
                    ? "[]" : row.getSourceReferences()).append("\n");
        }
    }

    private void appendIssues(StringBuilder builder, List<RuleValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            builder.append("\u65e0");
            return;
        }
        List<String> values = new ArrayList<String>();
        for (RuleValidationIssue issue : issues) {
            values.add(text(issue.getSeverity()) + "/" + text(issue.getField()) + "/" + text(issue.getMessage()));
        }
        builder.append(String.join("; ", values));
    }

    private List<AiReviewValidationItem> parseItems(String aiText) {
        String json = stripJson(aiText);
        try {
            if (json.startsWith("{")) {
                JsonItemWrapper wrapper = objectMapper.readValue(json, JsonItemWrapper.class);
                return wrapper.getItems() == null
                        ? Collections.<AiReviewValidationItem>emptyList() : wrapper.getItems();
            }
            return objectMapper.readValue(json, new TypeReference<List<AiReviewValidationItem>>() {});
        } catch (Exception ex) {
            log.warn("Parse AI review validation result failed: {}", aiText, ex);
            throw new BusinessException("AI \u590d\u6838\u7ed3\u679c\u89e3\u6790\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002", ex);
        }
    }

    private String stripJson(String text) {
        if (!StringUtils.hasText(text)) {
            return "[]";
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        if (cleaned.startsWith("{")) {
            return cleaned;
        }
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    private String callAi(String prompt) {
        if (aiModelConfigService != null) {
            return aiModelConfigService.generateForTask(prompt, AiPromptTask.INITIAL_REVIEW, false);
        }
        String resolvedApiKey = resolveApiKey();
        if (!StringUtils.hasText(resolvedApiKey)) {
            throw new BusinessException("AI api key is not configured. Please set OPENAI_API_KEY.");
        }
        if (usesDeepSeekPrompt()) {
            AiModelProfile profile = new AiModelProfile();
            profile.setProvider("deepseek");
            profile.setBaseUrl(baseUrl);
            profile.setModel(model);
            return new DeepSeekChatClient(objectMapper).generate(
                    profile, resolvedApiKey, AiPromptTask.INITIAL_REVIEW, prompt, timeoutSeconds, maxRetries);
        }
        ChatLanguageModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(resolvedApiKey)
                .modelName(model)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(maxRetries)
                .build();
        try {
            return chatModel.generate(prompt);
        } catch (RuntimeException ex) {
            log.error("AI review validation failed, model={}, baseUrl={}, timeoutSeconds={}, maxRetries={}",
                    model, baseUrl, timeoutSeconds, maxRetries, ex);
            if (isTimeout(ex)) {
                throw new BusinessException("AI \u590d\u6838\u8d85\u65f6\uff0c\u8bf7\u7f29\u5c0f\u9009\u4e2d\u884c\u6570\u6216\u7a0d\u540e\u91cd\u8bd5\u3002", ex);
            }
            throw new BusinessException("AI \u590d\u6838\u5931\u8d25\uff1a" + rootMessage(ex), ex);
        }
    }

    private String currentModel() {
        if (aiModelConfigService == null) return model;
        AiModelProfile profile = aiModelConfigService.getActiveProfile();
        return profile.getModel();
    }

    private boolean usesDeepSeekPrompt() {
        if (aiModelConfigService != null) {
            return aiModelConfigService.isDeepSeekActive();
        }
        return DeepSeekPromptProfile.matches("", baseUrl, model);
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedIOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        Throwable root = throwable;
        while (current != null) {
            root = current;
            current = current.getCause();
        }
        return root.getMessage() == null ? throwable.getClass().getSimpleName() : root.getMessage();
    }

    private String resolveApiKey() {
        if (StringUtils.hasText(apiKey)) {
            return apiKey;
        }
        String fromSystemProp = System.getProperty("OPENAI_API_KEY");
        if (!StringUtils.hasText(fromSystemProp)) {
            fromSystemProp = System.getProperty("DEEPSEEK_API_KEY");
        }
        if (StringUtils.hasText(fromSystemProp)) {
            return fromSystemProp;
        }
        String fromEnv = System.getenv("OPENAI_API_KEY");
        if (!StringUtils.hasText(fromEnv)) {
            fromEnv = System.getenv("DEEPSEEK_API_KEY");
        }
        return fromEnv;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static class JsonItemWrapper {
        private List<AiReviewValidationItem> items;

        public List<AiReviewValidationItem> getItems() {
            return items;
        }

        public void setItems(List<AiReviewValidationItem> items) {
            this.items = items;
        }
    }
}
