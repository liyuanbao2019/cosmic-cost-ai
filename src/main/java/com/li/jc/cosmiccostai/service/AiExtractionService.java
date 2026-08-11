package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.AiPromptResponse;
import com.li.jc.cosmiccostai.dto.AiModelProfile;
import com.li.jc.cosmiccostai.dto.ExtractResponse;
import com.li.jc.cosmiccostai.dto.RuleValidationIssue;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import com.li.jc.cosmiccostai.model.CosmicMovement;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final SplitRuleConfigService splitRuleConfigService;
    private final RequirementInputService requirementInputService;
    private final AiModelConfigService aiModelConfigService;
    private final AiGeneratedSplitQualityService aiGeneratedSplitQualityService;
    private final Set<String> runningProjects = ConcurrentHashMap.newKeySet();

    @Autowired
    public AiExtractionService(FileStorageService fileStorageService,
                               @Value("${cosmic.ai.base-url:}") String baseUrl,
                               @Value("${cosmic.ai.api-key:}") String apiKey,
                               @Value("${cosmic.ai.model:gpt-4o-mini}") String model,
                               @Value("${cosmic.ai.timeout-seconds:360}") long timeoutSeconds,
                               @Value("${cosmic.ai.max-retries:2}") int maxRetries,
                               SplitRuleConfigService splitRuleConfigService,
                               RequirementInputService requirementInputService,
                               AiModelConfigService aiModelConfigService,
                               AiGeneratedSplitQualityService aiGeneratedSplitQualityService) {
        this.fileStorageService = fileStorageService;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
        this.splitRuleConfigService = splitRuleConfigService;
        this.requirementInputService = requirementInputService;
        this.aiModelConfigService = aiModelConfigService;
        this.aiGeneratedSplitQualityService = aiGeneratedSplitQualityService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public AiExtractionService(FileStorageService fileStorageService,
                               String baseUrl,
                               String apiKey,
                               String model,
                               long timeoutSeconds,
                               int maxRetries,
                               String splitRulesPath) {
        this.fileStorageService = fileStorageService;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
        this.splitRuleConfigService = new SplitRuleConfigService("target/test-config/split-check-rules.md");
        this.requirementInputService = new RequirementInputService(fileStorageService);
        this.aiModelConfigService = null;
        this.aiGeneratedSplitQualityService = new AiGeneratedSplitQualityService(
                new RuleValidationService(this.splitRuleConfigService));
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
        if (!runningProjects.add(projectId)) {
            throw new BusinessException("AI 拆分正在进行，请等待当前任务完成。");
        }
        try {
            updateProcessingStatus(metadata, "AI_SPLITTING");
            return executeExtractionInternal(projectId, metadata, projectDir, requirementDetail, prompt);
        } catch (RuntimeException ex) {
            updateProcessingStatus(metadata, "AI_FAILED");
            throw ex;
        } finally {
            runningProjects.remove(projectId);
        }
    }

    private ExtractResponse executeExtractionInternal(String projectId,
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
        List<RuleValidationIssue> initialReviewIssues = aiGeneratedSplitQualityService.validate(functions);
        if (!initialReviewIssues.isEmpty()) {
            log.warn("AI extraction completed with issues reserved for manual initial review. "
                            + "projectId={}, issueCount={}, issues={}",
                    projectId, initialReviewIssues.size(),
                    aiGeneratedSplitQualityService.summarize(initialReviewIssues));
        }
        attachRequirementEvidence(functions, requirementDetail);

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
        metadata.setMaterialArchiveOutputPath(null);
        metadata.setRuleVersion(splitRuleConfigService.getRuleVersion());
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

    private void updateProcessingStatus(ProjectMetadata metadata, String status) {
        metadata.setStatus(status);
        metadata.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
        fileStorageService.saveMetadata(metadata);
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
        response.setModelName(currentModel());
        response.setPrompt(prompt);
        response.setPromptPath(Files.exists(promptPath) ? promptPath.toString() : "");
        return response;
    }

    private String resolveRequirementDetail(ProjectMetadata metadata, Path projectDir) {
        if (requirementInputService.isCustomized(projectDir) && StringUtils.hasText(metadata.getRequirementDetailPath())) {
            Path manualInputPath = Paths.get(metadata.getRequirementDetailPath());
            if (Files.exists(manualInputPath)) {
                String manualInput = readText(manualInputPath);
                if (StringUtils.hasText(manualInput)) {
                    return manualInput.trim();
                }
            }
        }
        if (StringUtils.hasText(metadata.getDocumentTextPath())) {
            Path documentTextPath = Paths.get(metadata.getDocumentTextPath());
            if (Files.exists(documentTextPath)) {
                String extracted = extractRequirementDetail(readText(documentTextPath));
                if (StringUtils.hasText(extracted)) {
                    Path requirementDetailPath = StringUtils.hasText(metadata.getRequirementDetailPath())
                            ? Paths.get(metadata.getRequirementDetailPath())
                            : projectDir.resolve("requirement-detail.txt");
                    String existing = Files.exists(requirementDetailPath) ? readText(requirementDetailPath).trim() : "";
                    if (!extracted.equals(existing)) {
                        writeText(requirementDetailPath, extracted);
                    }
                    if (!requirementDetailPath.toString().equals(metadata.getRequirementDetailPath())) {
                        metadata.setRequirementDetailPath(requirementDetailPath.toString());
                        fileStorageService.saveMetadata(metadata);
                    }
                    return extracted;
                }
            }
        }
        if (StringUtils.hasText(metadata.getRequirementDetailPath())) {
            Path requirementDetailPath = Paths.get(metadata.getRequirementDetailPath());
            if (Files.exists(requirementDetailPath)) {
                String requirementDetail = readText(requirementDetailPath);
                if (StringUtils.hasText(requirementDetail)) {
                    return requirementDetail.trim();
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
        return RequirementSectionExtractor.extract(documentText);
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
        String numberedRequirement = numberRequirementEvidence(requirementDetail);
        boolean deepSeekPrompt = usesDeepSeekPrompt();
        String outputRequirement = deepSeekPrompt
                ? "输出必须是严格JSON对象，顶层只能包含 functions 数组；不要Markdown代码块，不要解释，不要输出格式之外的额外字段。functions数组元素格式固定为："
                : "输出必须是严格JSON数组，不要Markdown代码块，不要解释，不要输出格式之外的额外字段。数组元素格式固定为：";
        String emptyOutput = deepSeekPrompt ? DeepSeekPromptProfile.emptyJson(AiPromptTask.EXTRACTION) : "[]";
        String prompt = "你是资深软件造价评估专家、COSMIC功能点分析师和软件需求分析专家。"
                + System.lineSeparator()
                + "请只根据下面的【需求细则】识别可用于COSMIC度量的真实功能用户需求。"
                + System.lineSeparator()
                + "禁止使用背景、目标、系统名称、建设必要性、非功能性要求等固定内容进行发挥。"
                + System.lineSeparator()
                + "输入会保留完整的“业务功能需求”章节，其中可能包含功能界面、流程图或截图文字；这些内容用于防止正文遗漏，不能单独虚构成功能点，应以功能描述和明确业务动作作为拆分依据。"
                + System.lineSeparator()
                + "抽取原则："
                + System.lineSeparator()
                + "1. 以COSMIC功能过程为粒度。一个功能过程应由功能用户触发，并能形成一组完整的数据移动响应。"
                + System.lineSeparator()
                + "2. 新增、修改、删除、查询、审核、发布、撤回、配置、发送、推送、导入、导出等不同业务动作，应拆成不同功能。"
                + System.lineSeparator()
                + "3. 同一业务动作涉及多个明确对象或渠道时，应按对象或渠道拆分；但不要把同一触发、同一业务目标下的输入、外部同步往返、读取、写入、输出拆成多个功能。"
                + "本系统向外部系统发送请求 X 后，对方在同一调用链中同步返回数据时，响应进入本系统可以再次记 E；只有对方稍后以回调/上报接口形成新的独立触发时，才必须另拆功能过程。"
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
                + "10. 先明确被度量系统边界，再逐行输出 movements。E 是功能用户跨边界进入本系统的数据，X 是本系统跨边界发出的数据；R/W 只针对本系统持久化数据组。"
                + System.lineSeparator()
                + "11. E 的 description 必须写真实进入本系统的触发数据或业务数据，并按真实发起方使用自然业务语言：用户/UI 主动操作写“发起XXX查询，提交地市、工单类型等查询条件”“提交新增客户信息”；"
                + "外部平台上报、回调才写“接收SEQ定界结果上报数据”；X 后的同步响应写“获取XX平台同步响应数据”。禁止用“接收+功能过程名+请求”的机械拼接方式，禁止出现“请求请求”或“接收向XX平台发送……”这类方向矛盾表达。"
                + System.lineSeparator()
                + "12. 同步接口响应与异步业务回调必须区分：本系统发出外部请求 X 后，对方在同一用户目标内同步返回的业务响应可作为后续 E，形成 E-X-E-W-X 或多组 X-E；"
                + "外部平台稍后调用本系统上报结果时，应另建“接收XX结果上报”功能过程。被调用接口的 ReturnCode/ReturnMessage 是本系统向调用方返回的 X，不是前一功能的响应 E。"
                + System.lineSeparator()
                + "13. movements 的数据属性应依次检索原文表格字段、接口/报文参数和正文明确出现的业务数据项。正文中的“六级地址、故障原因、ret、P01、U13”等明确数据项可直接作为属性，不要求必须是数据库或接口英文列名；不得自动补原文没有的通用字段。"
                + System.lineSeparator()
                + "14. 每个功能过程至少输出 2 个 movements，第一个必须是初始触发 E，最后一个必须是 W 或 X；后续 E 只能是有依据的外部同步响应，禁止无依据的连续 E。movements 不设最多 4 行限制，数量由原文支持的真实数据移动决定。"
                + System.lineSeparator()
                + "15. 功能过程必须以“动作+业务对象”命名，不能只写“查询”“管理”。不要把翻页、勾选/取消勾选、图层切换、自动填充、高亮渲染等纯前端动作拆成独立功能。"
                + System.lineSeparator()
                + "16. 校验、计算、解析、匹配、过滤、排序是数据操作，不单独计数据移动；只能根据真实跨边界输入输出或持久化读写计 E/R/W/X。"
                + System.lineSeparator()
                + "17. 同一功能过程中相同数据组传递同一批数据时，必须完整复用已明确属性。无载荷的定时/时钟触发 E 填“无业务数据”。只有原文和同一功能过程上下文都无法识别任何属性时才整格填“待确认”；禁止“字段名（待确认）”混写，禁止虚构字段或写“同上/见上”。"
                + System.lineSeparator()
                + "18. 【需求细则】每个非空段落带有 REQ-Lxxxx 编号。每个功能和每个 movement 必须填写实际支持它的 sourceReferences，并在 evidence 中摘录对应原文；不得编造不存在的编号。"
                + System.lineSeparator()
                + "19. 输出前对全部候选做三轮扫描：先按历史评审批注 N/NM/数字行号/O/OLD/UIUE/C/NR/算法/需求文档不涉及处理，再做当前输出的功能级语义查重，最后按子过程描述、数据移动类型、数据组、数据属性和功能逻辑检查复用。功能级重复/包含按 0，子过程级复用按 0.33，确有新增且有原文依据才按 1。"
                + System.lineSeparator()
                + "20. 严禁机械补“读取XXX相关数据”“保存XXX处理结果”“返回XXX处理结果”，也不得用请求编号/操作人/请求时间、业务编号/状态/内容、记录编号/处理状态/时间、结果编号/状态/提示信息等泛化属性制造差异。"
                + System.lineSeparator()
                + "输出要求："
                + System.lineSeparator()
                + outputRequirement
                + System.lineSeparator()
                + "{\"module\":\"模块名称\",\"function\":\"功能过程名称\",\"description\":\"功能描述\","
                + "\"functionalUser\":\"发起者：功能用户名称\\n接收者：被度量系统名称\","
                + "\"triggerEvent\":\"独立触发事件\","
                + "\"sequenceDiagram\":\"参与者A->参与者B;参与者B->参与者C\","
                + "\"evidence\":\"需求原文摘要\",\"sourceReferences\":[\"REQ-L0001\"],"
                + "\"movements\":[{\"type\":\"E/R/W/X之一\",\"description\":\"真实数据移动描述\","
                + "\"dataGroup\":\"原文数据组或待确认\",\"dataAttributes\":\"原文字段，顿号分隔，或待确认\","
                + "\"sequenceDiagram\":\"本次数据移动的发送方->接收方\","
                + "\"evidence\":\"本次数据移动的原文摘要\",\"sourceReferences\":[\"REQ-L0001\"]}]}"
                + System.lineSeparator()
                + "输出前请自检："
                + System.lineSeparator()
                + "1. 每一项都必须来自【需求细则】原文或其明确含义。"
                + System.lineSeparator()
                + "2. 不要输出重复功能；如果两个功能只是同一触发下的处理步骤或同步 X-E 往返，应合并；如果存在外部系统异步回调、结果上报或新的用户请求，则必须按独立触发拆分。"
                + System.lineSeparator()
                + "3. 不要输出需求细则之外的功能。"
                + System.lineSeparator()
                + "4. 如果确实无法识别任何功能，输出 " + emptyOutput + "。"
                + System.lineSeparator()
                + System.lineSeparator()
                + splitRulesPrompt
                + System.lineSeparator()
                + System.lineSeparator()
                + "【需求细则】"
                + System.lineSeparator()
                + numberedRequirement;
        return deepSeekPrompt
                ? DeepSeekPromptProfile.specialize(AiPromptTask.EXTRACTION, prompt, splitRuleConfigService.getRuleVersion())
                : prompt;
    }

    private String numberRequirementEvidence(String requirementDetail) {
        StringBuilder builder = new StringBuilder();
        for (RequirementEvidenceLine line : requirementEvidenceLines(requirementDetail)) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append('[').append(line.id).append("] ").append(line.text);
        }
        return builder.toString();
    }

    void attachRequirementEvidence(List<ExtractedFunction> functions, String requirementDetail) {
        List<RequirementEvidenceLine> lines = requirementEvidenceLines(requirementDetail);
        Map<String, RequirementEvidenceLine> byId = new LinkedHashMap<String, RequirementEvidenceLine>();
        for (RequirementEvidenceLine line : lines) {
            byId.put(line.id, line);
        }
        for (ExtractedFunction function : functions == null
                ? java.util.Collections.<ExtractedFunction>emptyList() : functions) {
            if (function == null) {
                continue;
            }
            String functionQuery = text(function.getModule()) + " " + text(function.getFunction()) + " "
                    + text(function.getDescription()) + " " + text(function.getTriggerEvent());
            List<String> functionReferences = traceReferences(function.getSourceReferences(), functionQuery, lines, byId);
            function.setSourceReferences(functionReferences);
            function.setEvidence(evidenceText(functionReferences, byId));
            for (CosmicMovement movement : function.getMovements() == null
                    ? java.util.Collections.<CosmicMovement>emptyList() : function.getMovements()) {
                if (movement == null) {
                    continue;
                }
                String movementQuery = functionQuery + " " + text(movement.getDescription()) + " "
                        + text(movement.getDataGroup()) + " " + text(movement.getDataAttributes());
                List<String> movementReferences = traceReferences(
                        movement.getSourceReferences(), movementQuery, lines, byId);
                if (movementReferences.isEmpty()) {
                    movementReferences = new ArrayList<String>(functionReferences);
                }
                movement.setSourceReferences(movementReferences);
                movement.setEvidence(evidenceText(movementReferences, byId));
            }
        }
    }

    private List<String> traceReferences(List<String> proposed,
                                         String query,
                                         List<RequirementEvidenceLine> lines,
                                         Map<String, RequirementEvidenceLine> byId) {
        List<String> valid = new ArrayList<String>();
        for (String value : proposed == null ? java.util.Collections.<String>emptyList() : proposed) {
            String reference = text(value).toUpperCase(java.util.Locale.ROOT);
            if (byId.containsKey(reference) && !valid.contains(reference)) {
                valid.add(reference);
            }
        }
        if (!valid.isEmpty() || lines.isEmpty()) {
            return valid;
        }
        Set<String> queryGrams = evidenceGrams(query);
        List<RequirementEvidenceLine> remaining = new ArrayList<RequirementEvidenceLine>(lines);
        for (int selected = 0; selected < 2 && !remaining.isEmpty(); selected++) {
            RequirementEvidenceLine best = null;
            int bestScore = -1;
            for (RequirementEvidenceLine line : remaining) {
                int score = evidenceOverlap(queryGrams, evidenceGrams(line.text));
                if (score > bestScore) {
                    best = line;
                    bestScore = score;
                }
            }
            if (best == null || (bestScore <= 0 && !valid.isEmpty())) {
                break;
            }
            valid.add(best.id);
            remaining.remove(best);
        }
        return valid;
    }

    private Set<String> evidenceGrams(String value) {
        String normalized = text(value).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
        Set<String> grams = new LinkedHashSet<String>();
        if (normalized.length() < 2) {
            if (StringUtils.hasText(normalized)) {
                grams.add(normalized);
            }
            return grams;
        }
        for (int i = 0; i < normalized.length() - 1; i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    private int evidenceOverlap(Set<String> left, Set<String> right) {
        int count = 0;
        for (String value : left) {
            if (right.contains(value)) {
                count++;
            }
        }
        return count;
    }

    private String evidenceText(List<String> references, Map<String, RequirementEvidenceLine> byId) {
        StringBuilder builder = new StringBuilder();
        for (String reference : references == null ? java.util.Collections.<String>emptyList() : references) {
            RequirementEvidenceLine line = byId.get(reference);
            if (line == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append(reference).append('：').append(line.text);
            if (builder.length() >= 500) {
                break;
            }
        }
        return builder.length() <= 500 ? builder.toString() : builder.substring(0, 500);
    }

    private List<RequirementEvidenceLine> requirementEvidenceLines(String requirementDetail) {
        List<RequirementEvidenceLine> result = new ArrayList<RequirementEvidenceLine>();
        String[] rawLines = text(requirementDetail).split("\\R");
        for (String rawLine : rawLines) {
            String value = text(rawLine);
            if (!StringUtils.hasText(value)) {
                continue;
            }
            result.add(new RequirementEvidenceLine(
                    String.format(java.util.Locale.ROOT, "REQ-L%04d", result.size() + 1), value));
        }
        return result;
    }

    private String buildSplitRulesPrompt() {
        String rules = splitRuleConfigService.getEffectiveRulesPrompt();
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
                + "接口必须按真实触发边界判断：本系统输出外部请求 X 后，同一调用链的同步响应可再次形成 E；外部平台稍后调用本系统的回调/上报接口，"
                + "才是新的被调用方过程，不得把异步回调误写成同步响应。"
                + System.lineSeparator()
                + "E 的名称描述真实进入本系统的数据或事件，不要求一律以“接收”开头，更不得机械拼成“接收+功能名+请求”。"
                + "movements 的属性优先使用原文表格/接口字段，也要提取正文明确出现的业务数据项；禁止自动生成原文没有的编号、操作人、状态、时间等字段。"
                + System.lineSeparator()
                + "每个功能过程至少包含 2 个子过程，首行为初始触发 E、末行为 W/X；后续 E 只允许有依据的外部同步响应，禁止无依据连续 E；不设置最多 4 行限制。"
                + System.lineSeparator()
                + "校验/计算/解析/匹配/过滤不单独计数据移动；翻页、勾选、图层切换、自动填充、高亮渲染等纯前端动作不单独拆分。"
                + System.lineSeparator()
                + "功能过程用“动作+业务对象”命名；同一数据表/接口/对象不按单字段、单维度或同一操作环节过度拆分。"
                + System.lineSeparator()
                + "必须先按历史评审判例与当前输出全局语义查重，再判新增/复用/利旧；功能级重复为 0，子过程级复用为 0.33，不能通过改名、字段换序或通用占位属性规避。"
                + System.lineSeparator()
                + "同一功能过程中相同数据组传递同一批数据时复用已明确属性；无载荷定时触发填“无业务数据”。只有穷尽原文和上下文仍无任何属性时才整格填“待确认”，不得混写“字段名（待确认）”、虚构字段或写“同上/见上”。"
                + System.lineSeparator()
                + "如果需求中出现校验、解析、过滤、刷新、日志、提示、调用、接口、配置、页面设计等表达，只有原文明示伴随真实跨边界或持久化数据移动时才转写为 E/R/W/X；纯数据操作、技术实现或界面动作不得强行计量。"
                + System.lineSeparator()
                + "不要直接输出“规则校验”“日志”“调用”“提示”“页面设计”等抽象功能名；只有具备独立功能用户、触发、业务对象、数据组和结果时才生成对应功能过程。"
                + System.lineSeparator()
                + "当前统一有效规则包如下："
                + System.lineSeparator()
                + rules.trim()
                + System.lineSeparator()
                + "【内部规则结束】";
    }

    private String callAi(String prompt) {
        if (aiModelConfigService != null) {
            return aiModelConfigService.generateForTask(prompt, AiPromptTask.EXTRACTION, false);
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
                    profile, resolvedApiKey, AiPromptTask.EXTRACTION, prompt, timeoutSeconds, maxRetries);
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

    private String currentModel() {
        if (aiModelConfigService == null) {
            return model;
        }
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

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static class RequirementEvidenceLine {
        private final String id;
        private final String text;

        private RequirementEvidenceLine(String id, String text) {
            this.id = id;
            this.text = text;
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
