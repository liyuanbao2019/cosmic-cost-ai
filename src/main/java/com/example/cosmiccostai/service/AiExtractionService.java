package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.ExtractResponse;
import com.example.cosmiccostai.model.ExtractedFunction;
import com.example.cosmiccostai.model.ProjectMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class AiExtractionService {

    private static final Logger log = LoggerFactory.getLogger(AiExtractionService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final long timeoutSeconds;
    private final int maxRetries;

    public AiExtractionService(FileStorageService fileStorageService,
                               @Value("${cosmic.ai.base-url:}") String baseUrl,
                               @Value("${cosmic.ai.api-key:}") String apiKey,
                               @Value("${cosmic.ai.model:gpt-4o-mini}") String model,
                               @Value("${cosmic.ai.timeout-seconds:180}") long timeoutSeconds,
                               @Value("${cosmic.ai.max-retries:2}") int maxRetries) {
        this.fileStorageService = fileStorageService;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public ExtractResponse extract(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);

        String documentText = readText(Paths.get(metadata.getDocumentTextPath()));
        String requirementDetail = extractRequirementDetail(documentText);
        if (!StringUtils.hasText(requirementDetail)) {
            throw new BusinessException("Requirement detail section is empty");
        }

        Path projectDir = fileStorageService.resolveProjectDir(projectId);
        Path requirementDetailPath = projectDir.resolve("requirement-detail.txt");
        writeText(requirementDetailPath, requirementDetail);

        String prompt = buildPrompt(requirementDetail);
        log.info("Calling AI extraction, projectId={}, model={}, baseUrl={}, requirementDetailLength={}, promptLength={}, timeoutSeconds={}",
                projectId, model, baseUrl, requirementDetail.length(), prompt.length(), timeoutSeconds);
        String aiResult = callAi(prompt);
        log.info("AI extraction completed, projectId={}, responseLength={}", projectId, aiResult == null ? 0 : aiResult.length());
        List<ExtractedFunction> functions = parseFunctions(aiResult);
        if (functions.isEmpty()) {
            throw new BusinessException("AI extracted no functions");
        }

        Path extractedPath = projectDir.resolve("extracted-functions.json");
        writeJson(extractedPath, functions);

        metadata.setRequirementDetailPath(requirementDetailPath.toString());
        metadata.setExtractedFunctionsPath(extractedPath.toString());
        metadata.setStatus("EXTRACTED");
        metadata.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
        fileStorageService.saveMetadata(metadata);

        ExtractResponse response = new ExtractResponse();
        response.setProjectId(projectId);
        response.setRequirementDetailPath(requirementDetailPath.toString());
        response.setExtractedFunctionsPath(extractedPath.toString());
        response.setFunctionCount(functions.size());
        response.setStatus(metadata.getStatus());
        response.setFunctions(functions);
        return response;
    }

    private void validateMetadata(ProjectMetadata metadata) {
        if (metadata == null) {
            throw new BusinessException("Project metadata is empty");
        }
        if (!StringUtils.hasText(metadata.getDocumentTextPath())) {
            throw new BusinessException("Project has not been parsed yet");
        }
    }

    private String extractRequirementDetail(String documentText) {
        String[] lines = documentText.split("\\r?\\n");
        StringBuilder detail = new StringBuilder();
        boolean collecting = false;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (!collecting) {
                if ("需求细则".equals(line)) {
                    collecting = true;
                }
                continue;
            }

            if (isNextFixedSection(line)) {
                break;
            }
            if (StringUtils.hasText(line)) {
                detail.append(line).append(System.lineSeparator());
            }
        }
        return detail.toString().trim();
    }

    private boolean isNextFixedSection(String line) {
        return "非功能性需求".equals(line)
                || "附加值调整因子说明".equals(line)
                || "系统现状".equals(line)
                || "功能架构图".equals(line)
                || "功能需求".equals(line);
    }

    private String buildPrompt(String requirementDetail) {
        return "你是资深软件造价评估专家、COSMIC功能点分析师和软件需求分析专家。"
                + System.lineSeparator()
                + "请只根据下面的【需求细则】识别可用于COSMIC度量的真实功能用户需求。"
                + System.lineSeparator()
                + "禁止使用背景、目标、系统名称、建设必要性、非功能性要求等固定内容进行发挥。"
                + System.lineSeparator()
                + "抽取原则："
                + System.lineSeparator()
                + "1. 以COSMIC功能过程为粒度。一个功能过程应由功能用户触发，并能形成一组完整的数据移动响应。"
                + System.lineSeparator()
                + "2. 新增、修改、删除、查询、审核、发布、撤回、配置、发送、推送、导入、导出等不同业务动作，应拆成不同功能。"
                + System.lineSeparator()
                + "3. 同一业务动作涉及多个明确对象或渠道时，应按对象或渠道拆分；但不要把同一功能过程内的输入、读取、写入、输出拆成多个功能。"
                + System.lineSeparator()
                + "4. 只抽取软件系统需要实现的业务功能，不抽取页面美化、性能、安全、兼容性、部署、培训、流程说明等非功能内容。"
                + System.lineSeparator()
                + "5. 需求描述里只有示例、说明、背景、目标、角色职责时，不要臆造功能。"
                + System.lineSeparator()
                + "6. module填写需求细则中最贴近该功能的一级业务模块名称；如果无法明确识别，使用最上层业务对象名称。"
                + System.lineSeparator()
                + "7. function使用5到20字左右的动宾短语，必须可直接作为Excel功能点拆分表中的“功能过程”。"
                + System.lineSeparator()
                + "8. description用一句话描述该功能的业务目的、主要处理对象和结果，避免空泛表述。"
                + System.lineSeparator()
                + "输出要求："
                + System.lineSeparator()
                + "输出必须是严格JSON数组，不要Markdown代码块，不要解释，不要额外字段。数组元素格式固定为："
                + System.lineSeparator()
                + "{\"module\":\"模块名称\",\"function\":\"功能名称\",\"description\":\"功能描述\"}"
                + System.lineSeparator()
                + "输出前请自检："
                + System.lineSeparator()
                + "1. 每一项都必须来自【需求细则】原文或其明确含义。"
                + System.lineSeparator()
                + "2. 不要输出重复功能；如果两个功能只是同一功能过程的步骤，应合并。"
                + System.lineSeparator()
                + "3. 不要输出需求细则之外的功能。"
                + System.lineSeparator()
                + "4. 如果确实无法识别任何功能，输出空数组[]。"
                + System.lineSeparator()
                + System.lineSeparator()
                + "【需求细则】"
                + System.lineSeparator()
                + requirementDetail;
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
            log.error("AI extraction failed, model={}, baseUrl={}, timeoutSeconds={}, maxRetries={}",
                    model, baseUrl, timeoutSeconds, maxRetries, ex);
            if (isTimeout(ex)) {
                throw new BusinessException("AI调用超时，请稍后重试，或调大 cosmic.ai.timeout-seconds。当前超时：" + timeoutSeconds + "秒", ex);
            }
            throw new BusinessException("AI调用失败：" + rootMessage(ex), ex);
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

    private List<ExtractedFunction> parseFunctions(String aiResult) {
        String json = stripJson(aiResult);
        try {
            if (json.startsWith("[")) {
                return objectMapper.readValue(json, new TypeReference<List<ExtractedFunction>>() {
                });
            }
            if (json.startsWith("{")) {
                JsonFunctionWrapper wrapper = objectMapper.readValue(json, JsonFunctionWrapper.class);
                if (wrapper.getFunctions() != null) {
                    return wrapper.getFunctions();
                }
            }
            throw new BusinessException("AI result is not a function array");
        } catch (IOException ex) {
            throw new BusinessException("Parse AI json result failed: " + aiResult, ex);
        }
    }

    private String stripJson(String aiResult) {
        if (aiResult == null) {
            return "";
        }
        String json = aiResult.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        return json;
    }

    private String readText(Path path) {
        if (!Files.exists(path)) {
            throw new BusinessException("Text file not found: " + path);
        }
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BusinessException("Read text file failed: " + path, ex);
        }
    }

    private void writeText(Path path, String text) {
        try {
            Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BusinessException("Write text file failed: " + path, ex);
        }
    }

    private void writeJson(Path path, List<ExtractedFunction> functions) {
        try {
            objectMapper.writeValue(path.toFile(), functions);
        } catch (IOException ex) {
            throw new BusinessException("Write extracted functions failed: " + path, ex);
        }
    }

    private static class JsonFunctionWrapper {

        private List<ExtractedFunction> functions;

        public List<ExtractedFunction> getFunctions() {
            return functions;
        }

        public void setFunctions(List<ExtractedFunction> functions) {
            this.functions = functions;
        }
    }
}
