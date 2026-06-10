package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.AiPromptResponse;
import com.example.cosmiccostai.dto.ExtractResponse;
import com.example.cosmiccostai.model.ExtractedFunction;
import com.example.cosmiccostai.model.ProjectMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.core.io.ClassPathResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiExtractionService {

    private static final Logger log = LoggerFactory.getLogger(AiExtractionService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String USER_EDITED_PROMPT_FLAG = "ai-prompt-user-edited.flag";

    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final long timeoutSeconds;
    private final int maxRetries;
    private final String splitRulesPath;

    public AiExtractionService(FileStorageService fileStorageService,
                               @Value("${cosmic.ai.base-url:}") String baseUrl,
                               @Value("${cosmic.ai.api-key:}") String apiKey,
                               @Value("${cosmic.ai.model:gpt-4o-mini}") String model,
                               @Value("${cosmic.ai.timeout-seconds:180}") long timeoutSeconds,
                               @Value("${cosmic.ai.max-retries:2}") int maxRetries,
                               @Value("${cosmic.rules.split-rules-path:classpath:/static/split-check-rules.md}") String splitRulesPath) {
        this.fileStorageService = fileStorageService;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
        this.splitRulesPath = splitRulesPath;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public ExtractResponse extract(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);

        Path projectDir = fileStorageService.resolveProjectDir(projectId);
        String requirementDetail = resolveRequirementDetail(metadata, projectDir);

        String prompt = buildPrompt(requirementDetail);
        Path promptPath = projectDir.resolve("ai-prompt.txt");
        writeText(promptPath, prompt);
        clearUserEditedPromptFlag(projectDir);
        return executeExtraction(projectId, metadata, projectDir, requirementDetail, prompt);
    }

    public ExtractResponse extractWithPrompt(String projectId, String prompt) {
        if (!StringUtils.hasText(prompt)) {
            throw new BusinessException("AI提示词不能为空");
        }
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);

        Path projectDir = fileStorageService.resolveProjectDir(projectId);
        String requirementDetail = resolveRequirementDetail(metadata, projectDir);
        Path promptPath = projectDir.resolve("ai-prompt.txt");
        writeText(promptPath, prompt);
        writeText(projectDir.resolve(USER_EDITED_PROMPT_FLAG), "true");
        return executeExtraction(projectId, metadata, projectDir, requirementDetail, prompt);
    }

    private ExtractResponse executeExtraction(String projectId,
                                              ProjectMetadata metadata,
                                              Path projectDir,
                                              String requirementDetail,
                                              String prompt) {
        log.info("Calling AI extraction, projectId={}, model={}, baseUrl={}, requirementDetailLength={}, promptLength={}, timeoutSeconds={}",
                projectId, model, baseUrl, requirementDetail.length(), prompt.length(), timeoutSeconds);
        String aiResult = callAi(prompt);
        log.info("AI extraction completed, projectId={}, responseLength={}", projectId, aiResult == null ? 0 : aiResult.length());
        List<ExtractedFunction> functions = parseFunctions(aiResult);
        if (functions.isEmpty()) {
            log.warn("AI returned no functions, projectId={}, using local fallback extraction", projectId);
            functions = fallbackFunctions(requirementDetail);
        }
        if (functions.isEmpty()) {
            throw new BusinessException("AI未识别到功能点。请在需求文档中使用“需求内容/需求细则/业务功能/功能描述”等大标题，并在标题下用（1）（2）列出每个规则、字段或业务动作。");
        }

        Path requirementDetailPath = projectDir.resolve("requirement-detail.txt");
        Path extractedPath = projectDir.resolve("extracted-functions.json");
        writeJson(extractedPath, functions);

        metadata.setRequirementDetailPath(requirementDetailPath.toString());
        metadata.setExtractedFunctionsPath(extractedPath.toString());
        metadata.setCosmicResultsPath(null);
        metadata.setFunctionSplitRowsPath(null);
        metadata.setExcelOutputPath(null);
        metadata.setBusinessRequirementOutputPath(null);
        metadata.setWordOutputPath(null);
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

    public AiPromptResponse getPrompt(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);

        Path projectDir = fileStorageService.resolveProjectDir(projectId);
        Path promptPath = projectDir.resolve("ai-prompt.txt");
        String prompt;
        String requirementDetail = resolveRequirementDetail(metadata, projectDir);
        if (Files.exists(promptPath) && Files.exists(projectDir.resolve(USER_EDITED_PROMPT_FLAG))) {
            prompt = readText(promptPath);
        } else if (Files.exists(promptPath) && !"PARSED".equals(metadata.getStatus())) {
            prompt = readText(promptPath);
            String expectedPrompt = buildPrompt(requirementDetail);
            if (!expectedPrompt.equals(prompt)) {
                prompt = expectedPrompt;
                writeText(promptPath, prompt);
            }
        } else {
            prompt = buildPrompt(requirementDetail);
            writeText(promptPath, prompt);
        }

        AiPromptResponse response = new AiPromptResponse();
        response.setProjectId(projectId);
        response.setModelName(model);
        response.setPrompt(prompt);
        response.setPromptPath(Files.exists(promptPath) ? promptPath.toString() : "");
        return response;
    }

    private String resolveRequirementDetail(ProjectMetadata metadata, Path projectDir) {
        if (StringUtils.hasText(metadata.getRequirementDetailPath())) {
            Path requirementDetailPath = Paths.get(metadata.getRequirementDetailPath());
            if (Files.exists(requirementDetailPath)) {
                String requirementDetail = readText(requirementDetailPath);
                if (StringUtils.hasText(requirementDetail)) {
                    return requirementDetail;
                }
            }
        }

        String documentText = readText(Paths.get(metadata.getDocumentTextPath()));
        String requirementDetail = extractRequirementDetail(documentText);
        if (!StringUtils.hasText(requirementDetail)) {
            throw new BusinessException("Requirement detail section is empty");
        }
        Path requirementDetailPath = projectDir.resolve("requirement-detail.txt");
        writeText(requirementDetailPath, requirementDetail);
        metadata.setRequirementDetailPath(requirementDetailPath.toString());
        fileStorageService.saveMetadata(metadata);
        return requirementDetail;
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
                if (isRequirementSection(line)) {
                    collecting = true;
                }
                continue;
            }

            if (isSectionBoundary(line)) {
                break;
            }
            if (StringUtils.hasText(line)) {
                detail.append(line).append(System.lineSeparator());
            }
        }
        return detail.toString().trim();
    }

    private boolean isRequirementSection(String line) {
        String normalized = normalizeSectionTitle(line);
        return "需求内容".equals(normalized)
                || "需求细则".equals(normalized)
                || "业务功能".equals(normalized)
                || "功能描述".equals(normalized);
    }

    private boolean isSectionBoundary(String line) {
        String normalized = normalizeSectionTitle(line);
        if (!StringUtils.hasText(normalized)) {
            return false;
        }
        if (isRequirementSection(line)) {
            return false;
        }
        return "非功能性需求".equals(normalized)
                || "非功能需求".equals(normalized)
                || "附加值调整因子说明".equals(normalized)
                || "系统现状".equals(normalized)
                || "功能架构图".equals(normalized)
                || "总体描述".equals(normalized)
                || "建设目标".equals(normalized)
                || "建设必要性".equals(normalized)
                || "背景及目标".equals(normalized)
                || "归属系统".equals(normalized)
                || isTopLevelSectionHeading(line);
    }

    private String normalizeSectionTitle(String line) {
        if (!StringUtils.hasText(line)) {
            return "";
        }
        String value = line.trim()
                .replaceAll("\\s+", "")
                .replaceAll("^[一二三四五六七八九十]+[、.．,，]?+", "")
                .replaceAll("^\\(?\\d+\\)?[、.．,，]?+", "")
                .replaceAll("^第[一二三四五六七八九十\\d]+[章节条][、.．,，]?+", "")
                .replaceAll("[:：]+$", "");
        return value.trim();
    }

    private boolean isTopLevelSectionHeading(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        String value = line.trim();
        if (value.startsWith("（") || value.startsWith("(") || value.matches("^\\d+\\.\\d+.*")) {
            return false;
        }
        return value.matches("^[一二三四五六七八九十]+[、.．]\\s*\\S.{0,60}$")
                || value.matches("^第[一二三四五六七八九十\\d]+[章节条]\\s*\\S.{0,60}$")
                || value.matches("^\\d+[、.．]\\s*\\D.{0,60}$");
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String buildPrompt(String requirementDetail) {
        String splitRulesPrompt = buildSplitRulesPrompt();
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
                + "4.1 规则优化、字段/明细优化、报表明细优化、接口协议优化，只要改变系统处理逻辑、数据字段、计算规则或输出结果，就应作为功能需求抽取；不要因为标题是规则优化或字段优化就输出空数组。"
                + System.lineSeparator()
                + "5. 需求描述里只有示例、说明、背景、目标、角色职责时，不要臆造功能。"
                + System.lineSeparator()
                + "6. module填写需求细则中最贴近该功能的一级业务模块名称；如果无法明确识别，使用最上层业务对象名称。"
                + System.lineSeparator()
                + "7. function使用5到20字左右的动宾短语，必须可直接作为Excel功能点拆分表中的“功能过程”。"
                + System.lineSeparator()
                + "8. description必须由大模型根据【需求细则】生成，描述该功能的业务目的、主要处理对象、关键处理逻辑和结果；后续Excel“功能描述”会在此基础上结合子过程描述生成，避免只复述功能名称。"
                + System.lineSeparator()
                + "9. sequenceDiagram可填写该功能过程的默认时序图参与者流向，格式必须是“参与者A->参与者B”，多个步骤用英文分号分隔；最终Excel会按COSMIC数据移动类型固化时序图列。"
                + System.lineSeparator()
                + "输出要求："
                + System.lineSeparator()
                + "输出必须是严格JSON数组，不要Markdown代码块，不要解释，不要输出格式之外的额外字段。数组元素格式固定为："
                + System.lineSeparator()
                + "{\"module\":\"模块名称\",\"function\":\"功能名称\",\"description\":\"功能描述\",\"sequenceDiagram\":\"用户->系统;系统->用户\"}"
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
                + splitRulesPrompt
                + System.lineSeparator()
                + System.lineSeparator()
                + "【需求细则】"
                + System.lineSeparator()
                + requirementDetail;
    }

    private String buildSplitRulesPrompt() {
        String rules = readSplitRules();
        if (!StringUtils.hasText(rules)) {
            return "";
        }
        return "【内部拆分校验与可计量改写规则】"
                + System.lineSeparator()
                + "以下规则来自团队历史拆分经验。原始需求文档拆分时必须优先遵守。"
                + System.lineSeparator()
                + "注意：命中高风险关键词时，不要简单丢弃该功能；应改写为专家可认可的开发功能和明确数据移动。"
                + System.lineSeparator()
                + "改写目标：把抽象表述改成“谁输入了什么 / 系统读取了什么 / 系统写入或生成了什么 / 系统输出了什么”。"
                + System.lineSeparator()
                + "如果需求中出现校验、解析、过滤、刷新、日志、提示、调用、接口、配置、页面设计等表达，必须尽量转写为可计量的 E/R/W/X 数据移动表达。"
                + System.lineSeparator()
                + "不要直接输出“规则校验”“日志”“调用”“提示”“页面设计”等抽象功能名；需要生成具备业务对象、数据组、数据属性和处理结果的功能过程。"
                + System.lineSeparator()
                + "原始规则文本如下："
                + System.lineSeparator()
                + rules.trim()
                + System.lineSeparator()
                + "【内部规则结束】";
    }

    private String readSplitRules() {
        if (!StringUtils.hasText(splitRulesPath)) {
            return "";
        }
        if (splitRulesPath.startsWith("classpath:")) {
            String resourcePath = splitRulesPath.substring("classpath:".length());
            if (resourcePath.startsWith("/")) {
                resourcePath = resourcePath.substring(1);
            }
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.warn("Split rules classpath resource not found: {}", splitRulesPath);
                return "";
            }
            try (InputStream inputStream = resource.getInputStream()) {
                return readAll(inputStream);
            } catch (IOException ex) {
                log.warn("Read split rules classpath resource failed: {}", splitRulesPath, ex);
                return "";
            }
        }
        Path path = Paths.get(splitRulesPath);
        if (!Files.exists(path)) {
            Path projectPath = Paths.get("").toAbsolutePath().resolve(splitRulesPath);
            if (Files.exists(projectPath)) {
                path = projectPath;
            }
        }
        if (!Files.exists(path)) {
            log.warn("Split rules file not found: {}", splitRulesPath);
            return "";
        }
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("Read split rules file failed: {}", path, ex);
            return "";
        }
    }

    private String readAll(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        while ((read = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
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

    private List<ExtractedFunction> fallbackFunctions(String requirementDetail) {
        List<ExtractedFunction> functions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<RequirementBlock> blocks = splitRequirementBlocks(requirementDetail);
        for (int i = 0; i < blocks.size(); i++) {
            RequirementBlock block = blocks.get(i);
            String body = block.body.toString();
            if (!StringUtils.hasText(body) || !looksLikeExtractableRequirement(block.title + body)) {
                continue;
            }
            String function = fallbackFunctionName(block.title, body, i + 1);
            if (!StringUtils.hasText(function) || !seen.add(function)) {
                continue;
            }
            String module = fallbackModuleName(block.title, body);
            String description = fallbackDescription(function, body);
            functions.add(new ExtractedFunction(module, function, description, "用户->系统;系统->后台服务;后台服务->用户"));
        }
        return functions;
    }

    private List<RequirementBlock> splitRequirementBlocks(String text) {
        List<RequirementBlock> blocks = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return blocks;
        }
        RequirementBlock current = null;
        String[] lines = text.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (!StringUtils.hasText(line)) {
                continue;
            }
            RequirementHeading heading = parseRequirementHeading(line);
            if (heading != null) {
                if (current != null) {
                    blocks.add(current);
                }
                current = new RequirementBlock(heading.title);
                if (StringUtils.hasText(heading.inlineBody)) {
                    current.append(heading.inlineBody);
                }
                continue;
            }
            if (current == null) {
                current = new RequirementBlock(firstSentence(line));
            }
            current.append(line);
        }
        if (current != null) {
            blocks.add(current);
        }
        return blocks;
    }

    private RequirementHeading parseRequirementHeading(String line) {
        Matcher matcher = Pattern.compile("^[（(]?\\s*\\d+\\s*[）)]?[、.．]?\\s*(.{2,40}?)(?:[:：]\\s*(.*))?$").matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        String title = cleanHeading(matcher.group(1));
        String inlineBody = matcher.group(2) == null ? "" : matcher.group(2).trim();
        if (!StringUtils.hasText(title) || title.length() > 30) {
            return null;
        }
        if (!line.contains("：") && !line.contains(":") && looksLikeFullSentence(title)) {
            return null;
        }
        return new RequirementHeading(title, inlineBody);
    }

    private String cleanHeading(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replaceAll("\\s+", "")
                .replaceAll("[：:。；;,.，、]+$", "")
                .trim();
    }

    private boolean looksLikeFullSentence(String text) {
        return text.contains("，") || text.contains("。") || text.contains("；") || text.length() > 18;
    }

    private boolean looksLikeExtractableRequirement(String text) {
        return containsAny(text,
                "新增", "优化", "变更", "修改", "删除", "查询", "统计", "保存", "更新", "导入", "导出",
                "规则", "字段", "明细", "接口", "协议", "报表", "剔重", "判断", "保留", "使用", "调整");
    }

    private String fallbackFunctionName(String title, String body, int index) {
        String cleanedTitle = cleanHeading(title);
        if (StringUtils.hasText(cleanedTitle) && cleanedTitle.length() >= 4 && cleanedTitle.length() <= 20) {
            return cleanedTitle;
        }
        String source = title + body;
        if (containsAny(source, "统计", "剔重", "规则")) {
            return "优化统计规则";
        }
        if (containsAny(source, "明细", "字段")) {
            return "优化明细字段";
        }
        if (containsAny(source, "接口", "协议")) {
            return "优化接口协议";
        }
        if (containsAny(source, "报表")) {
            return "优化报表明细";
        }
        return "处理需求规则" + index;
    }

    private String fallbackModuleName(String title, String body) {
        String source = title + body;
        if (containsAny(source, "明细", "字段")) {
            return "明细字段";
        }
        if (containsAny(source, "接口", "协议")) {
            return "接口协议";
        }
        if (containsAny(source, "报表")) {
            return "报表";
        }
        if (containsAny(source, "统计", "剔重", "规则")) {
            return "统计规则";
        }
        if (containsAny(source, "宽带", "家宽")) {
            return "宽带业务";
        }
        return "需求规则";
    }

    private String fallbackDescription(String function, String body) {
        String sentence = firstSentence(body);
        if (!StringUtils.hasText(sentence)) {
            return function;
        }
        return function + "，" + sentence;
    }

    private String firstSentence(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        Matcher matcher = Pattern.compile("^(.{1,120}?[。；;])").matcher(compact);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return compact.length() > 120 ? compact.substring(0, 120) : compact;
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

    private void clearUserEditedPromptFlag(Path projectDir) {
        try {
            Files.deleteIfExists(projectDir.resolve(USER_EDITED_PROMPT_FLAG));
        } catch (IOException ex) {
            throw new BusinessException("Clear user edited prompt flag failed: " + projectDir, ex);
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

    private static class RequirementBlock {
        private final String title;
        private final StringBuilder body = new StringBuilder();

        private RequirementBlock(String title) {
            this.title = title == null ? "" : title;
        }

        private void append(String line) {
            if (StringUtils.hasText(line)) {
                body.append(line.trim()).append(System.lineSeparator());
            }
        }
    }

    private static class RequirementHeading {
        private final String title;
        private final String inlineBody;

        private RequirementHeading(String title, String inlineBody) {
            this.title = title;
            this.inlineBody = inlineBody;
        }
    }
}
