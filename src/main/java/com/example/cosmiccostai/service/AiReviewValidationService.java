package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.AiReviewValidationItem;
import com.example.cosmiccostai.dto.AiReviewValidationRequest;
import com.example.cosmiccostai.dto.AiReviewValidationResponse;
import com.example.cosmiccostai.dto.AiReviewValidationRow;
import com.example.cosmiccostai.dto.RuleValidationIssue;
import com.example.cosmiccostai.model.FunctionSplitRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    public AiReviewValidationService(@Value("${cosmic.ai.base-url:}") String baseUrl,
                                     @Value("${cosmic.ai.api-key:}") String apiKey,
                                     @Value("${cosmic.ai.model:gpt-4o-mini}") String model,
                                     @Value("${cosmic.ai.timeout-seconds:180}") long timeoutSeconds,
                                     @Value("${cosmic.ai.max-retries:2}") int maxRetries) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
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
        AiReviewValidationResponse response = new AiReviewValidationResponse();
        response.setRequestedCount(rows.size());
        response.setModel(model);
        response.setItems(items);
        return response;
    }

    private String buildPrompt(List<AiReviewValidationRow> rows) {
        StringBuilder builder = new StringBuilder();
        builder.append("\u4f60\u662f COSMIC \u529f\u80fd\u70b9\u62c6\u5206\u4e13\u5bb6\uff0c\u8bf7\u590d\u6838\u5df2\u901a\u8fc7\u7cfb\u7edf\u89c4\u5219\u521d\u7b5b\u7684\u9ad8\u98ce\u9669\u884c\u3002\n");
        builder.append("\u76ee\u6807\uff1a\u5224\u65ad\u8fd9\u4e9b\u884c\u662f\u5426\u771f\u7684\u4e0d\u5229\u4e8e COSMIC \u8ba1\u91cf\uff0c\u5e76\u7ed9\u51fa\u4eba\u5de5\u53ef\u91c7\u7eb3\u7684\u4fee\u6539\u5efa\u8bae\u3002\n");
        builder.append("\u8981\u6c42\uff1a\n");
        builder.append("1. \u4e0d\u8981\u81ea\u52a8\u6539\u6570\u636e\uff0c\u53ea\u8f93\u51fa\u590d\u6838\u610f\u89c1\u548c\u5efa\u8bae\u8868\u8fbe\u3002\n");
        builder.append("2. \u4f18\u5148\u68c0\u67e5\u529f\u80fd\u8fc7\u7a0b\u662f\u5426\u53ef\u8ba1\u91cf\uff0c\u662f\u5426\u660e\u786e\u4f53\u73b0\u6570\u636e\u79fb\u52a8\u3002\n");
        builder.append("3. \u68c0\u67e5\u5b50\u8fc7\u7a0b\u63cf\u8ff0\u3001\u6570\u636e\u7ec4\u3001\u6570\u636e\u5c5e\u6027\u662f\u5426\u5339\u914d E/R/W/X \u7c7b\u578b\u3002\n");
        builder.append("4. \u5fc5\u987b\u7ed3\u5408 sameFunctionProcessRows \u4e2d\u540c\u4e00\u529f\u80fd\u8fc7\u7a0b\u7684\u5168\u90e8\u5b50\u8fc7\u7a0b\u5224\u65ad\uff0c\u4e0d\u80fd\u53ea\u770b selectedRow\u3002\n");
        builder.append("5. \u5982\u679c sameFunctionProcessRows \u4e2d\u5df2\u7ecf\u5b58\u5728 W \u6216 X\uff0c\u4e0d\u8981\u5224\u65ad\u4e3a\u7f3a\u5c11\u8f93\u51fa\u6216\u5199\u5165\uff1b\u5e94\u8f6c\u800c\u5224\u65ad\u9009\u4e2d\u884c\u672c\u8eab\u7684\u8868\u8fbe\u662f\u5426\u53ef\u8ba1\u91cf\u3002\n");
        builder.append("6. verdict \u53ea\u80fd\u662f CONFIRMED_RISK\u3001FALSE_POSITIVE\u3001NEED_REVIEW \u4e4b\u4e00\u3002\n");
        builder.append("7. suggestion \u5fc5\u987b\u6709\u503c\uff1aCONFIRMED_RISK \u5fc5\u987b\u7ed9\u51fa\u53ef\u91c7\u7eb3\u7684\u4fee\u6539\u5efa\u8bae\uff1bFALSE_POSITIVE \u8bf7\u5199\u660e\u201c\u65e0\u9700\u4fee\u6539\u201d\u548c\u5224\u65ad\u539f\u56e0\uff1bNEED_REVIEW \u8bf7\u5199\u660e\u9700\u4eba\u5de5\u786e\u8ba4\u7684\u70b9\u3002\n");
        builder.append("8. CONFIRMED_RISK \u65f6\uff0c\u5c3d\u91cf\u586b\u5199 suggestedFunctionProcess\u3001suggestedSubProcessDescription\u3001suggestedDataGroup\u3001suggestedDataAttributes \u4e2d\u7684\u53ef\u4fee\u6539\u5b57\u6bb5\uff0c\u4fbf\u4e8e\u524d\u53f0\u4e00\u952e\u91c7\u7eb3\u3002\n");
        builder.append("9. \u53ea\u8f93\u51fa JSON \u6570\u7ec4\uff0c\u4e0d\u8981 markdown\u3001\u4e0d\u8981\u89e3\u91ca\u6027\u524d\u540e\u7f00\u3002\n");
        builder.append("\u8f93\u51fa\u5b57\u6bb5\uff1arowIndex,functionProcess,verdict,reason,suggestion,suggestedFunctionProcess,suggestedSubProcessDescription,suggestedDataGroup,suggestedDataAttributes,confidence\u3002\n\n");
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
            builder.append("  reuseDegree: ").append(text(row == null ? null : row.getReuseDegree())).append("\n");
            builder.append("  ruleIssues: ");
            appendIssues(builder, item.getIssues());
            builder.append("\n");
            builder.append("  sameFunctionProcessRows:\n");
            appendContextRows(builder, item.getContextRows());
        }
        return builder.toString();
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
            return objectMapper.readValue(json, new TypeReference<List<AiReviewValidationItem>>() {
            });
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
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    private String callAi(String prompt) {
        String resolvedApiKey = resolveApiKey();
        if (!StringUtils.hasText(resolvedApiKey)) {
            throw new BusinessException("AI api key is not configured. Please set OPENAI_API_KEY.");
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
}
