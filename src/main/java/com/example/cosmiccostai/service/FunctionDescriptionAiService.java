package com.example.cosmiccostai.service;

import com.example.cosmiccostai.model.FunctionSplitRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FunctionDescriptionAiService {

    private static final Logger log = LoggerFactory.getLogger(FunctionDescriptionAiService.class);
    private static final int MAX_GROUPS_PER_REQUEST = 20;
    private static final int MAX_PROMPT_CHARS_PER_REQUEST = 12000;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final long timeoutSeconds;
    private final int maxRetries;

    public FunctionDescriptionAiService() {
        this("", "", "", 180, 2);
    }

    @Autowired
    public FunctionDescriptionAiService(@Value("${cosmic.ai.base-url:}") String baseUrl,
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

    public Map<String, String> generateDescriptions(Map<String, List<FunctionSplitRow>> groups) {
        if (groups == null || groups.isEmpty() || !StringUtils.hasText(baseUrl) || !StringUtils.hasText(resolveApiKey())) {
            log.info("Skip AI function description generation. groups={}, baseUrlConfigured={}, apiKeyConfigured={}",
                    groups == null ? 0 : groups.size(), StringUtils.hasText(baseUrl), StringUtils.hasText(resolveApiKey()));
            return Collections.emptyMap();
        }
        long startedAt = System.currentTimeMillis();
        log.info("AI function description generation started. groups={}, model={}, baseUrl={}, timeoutSeconds={}, maxRetries={}, maxGroupsPerRequest={}, maxPromptChars={}",
                groups.size(), model, baseUrl, timeoutSeconds, maxRetries, MAX_GROUPS_PER_REQUEST, MAX_PROMPT_CHARS_PER_REQUEST);
        Map<String, String> result = new LinkedHashMap<String, String>();
        Map<String, List<FunctionSplitRow>> batch = new LinkedHashMap<String, List<FunctionSplitRow>>();
        for (Map.Entry<String, List<FunctionSplitRow>> entry : groups.entrySet()) {
            if (!batch.isEmpty() && shouldFlushBatch(batch, entry)) {
                result.putAll(generateDescriptionsBatch(batch));
                batch.clear();
            }
            batch.put(entry.getKey(), entry.getValue());
        }
        if (!batch.isEmpty()) {
            result.putAll(generateDescriptionsBatch(batch));
        }
        log.info("AI function description generation completed. groups={}, generated={}, elapsedMs={}",
                groups.size(), result.size(), System.currentTimeMillis() - startedAt);
        return result;
    }

    private boolean shouldFlushBatch(Map<String, List<FunctionSplitRow>> batch, Map.Entry<String, List<FunctionSplitRow>> next) {
        if (batch.size() >= MAX_GROUPS_PER_REQUEST) {
            return true;
        }
        Map<String, List<FunctionSplitRow>> probe = new LinkedHashMap<String, List<FunctionSplitRow>>(batch);
        probe.put(next.getKey(), next.getValue());
        return buildPrompt(probe).length() > MAX_PROMPT_CHARS_PER_REQUEST;
    }

    private Map<String, String> generateDescriptionsBatch(Map<String, List<FunctionSplitRow>> batch) {
        String prompt = buildPrompt(batch);
        long startedAt = System.currentTimeMillis();
        log.info("AI function description batch started. groups={}, promptLength={}", batch.size(), prompt.length());
        try {
            String aiText = callAi(prompt);
            Map<String, String> descriptions = parseDescriptions(aiText);
            log.info("AI function description batch completed. groups={}, generated={}, responseLength={}, elapsedMs={}",
                    batch.size(), descriptions.size(), aiText == null ? 0 : aiText.length(), System.currentTimeMillis() - startedAt);
            return descriptions;
        } catch (RuntimeException ex) {
            log.warn("AI function description batch failed, fallback to local description. groups={}, promptLength={}, elapsedMs={}",
                    batch.size(), prompt.length(), System.currentTimeMillis() - startedAt, ex);
            return Collections.emptyMap();
        }
    }

    private String buildPrompt(Map<String, List<FunctionSplitRow>> groups) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是COSMIC功能点拆分专家。请根据每个功能过程下的全部子过程，生成面向专家评审的功能描述。").append(System.lineSeparator());
        builder.append("要求：").append(System.lineSeparator());
        builder.append("1. 每个功能过程输出一段中文功能描述，80到180字。").append(System.lineSeparator());
        builder.append("2. 不能只复述单个子过程，要概括整个功能过程从输入、读取、写入到输出的业务目的和结果。").append(System.lineSeparator());
        builder.append("3. 描述要拟人化、业务化，说清楚系统为用户完成什么事、处理哪些业务数据、形成什么结果。").append(System.lineSeparator());
        builder.append("4. 不要使用“支撑”“赋能”“实现技术能力”等空泛词，不要编造子过程之外的新功能。").append(System.lineSeparator());
        builder.append("5. 只输出严格JSON对象，key为我提供的key，value为功能描述，不要Markdown。").append(System.lineSeparator());
        builder.append(System.lineSeparator()).append("待生成内容：").append(System.lineSeparator());
        for (Map.Entry<String, List<FunctionSplitRow>> entry : groups.entrySet()) {
            List<FunctionSplitRow> rows = entry.getValue();
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            FunctionSplitRow first = rows.get(0);
            builder.append("- key: ").append(entry.getKey()).append(System.lineSeparator());
            builder.append("  功能过程: ").append(text(first.getFunctionProcess())).append(System.lineSeparator());
            builder.append("  模块: ").append(text(first.getLevel1Module())).append(" / ")
                    .append(text(first.getLevel2Module())).append(" / ").append(text(first.getLevel3Module())).append(System.lineSeparator());
            builder.append("  子过程:").append(System.lineSeparator());
            for (FunctionSplitRow row : rows) {
                builder.append("    - 类型: ").append(text(row.getMovementType()))
                        .append("；子过程: ").append(text(row.getSubProcessDescription()))
                        .append("；数据组: ").append(text(row.getDataGroup()))
                        .append("；数据属性: ").append(text(row.getDataAttributes()))
                        .append(System.lineSeparator());
            }
        }
        return builder.toString();
    }

    private String callAi(String prompt) {
        ChatLanguageModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(resolveApiKey())
                .modelName(model)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(maxRetries)
                .build();
        return chatModel.generate(prompt);
    }

    private Map<String, String> parseDescriptions(String aiText) {
        String json = stripCodeFence(aiText);
        Map<String, String> parsed;
        try {
            parsed = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (IOException ex) {
            throw new IllegalArgumentException("Parse AI function descriptions failed", ex);
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : parsed.entrySet()) {
            if (StringUtils.hasText(entry.getKey()) && StringUtils.hasText(entry.getValue())) {
                result.put(entry.getKey().trim(), entry.getValue().trim());
            }
        }
        return result;
    }

    private String stripCodeFence(String value) {
        String json = value == null ? "" : value.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        return json;
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
