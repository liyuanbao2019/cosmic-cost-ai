package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.RequirementCoverageModule;
import com.example.cosmiccostai.dto.RequirementCoverageResponse;
import com.example.cosmiccostai.model.ProjectMetadata;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verifies that every business function module found in the Word body is present in the AI input. */
@Service
public class RequirementCoverageService {

    private static final Pattern BUSINESS_SECTION = Pattern.compile("^\\d+[.．]?\\s*业务功能(?:需求)?\\s*$");
    // The space after the full number is essential: without it, "2.1.1.1 功能描述"
    // can be incorrectly shortened to module "2.1.1" with title "1 功能描述".
    private static final Pattern MODULE_HEADING = Pattern.compile("^(\\d+(?:[.．]\\d+)+)[.．]?\\s+(.+)$");

    private final FileStorageService fileStorageService;
    private final RequirementInputService requirementInputService;

    @Autowired
    public RequirementCoverageService(FileStorageService fileStorageService, RequirementInputService requirementInputService) {
        this.fileStorageService = fileStorageService;
        this.requirementInputService = requirementInputService;
    }

    RequirementCoverageService(FileStorageService fileStorageService) {
        this(fileStorageService, new RequirementInputService(fileStorageService));
    }

    public RequirementCoverageResponse getCoverage(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        String documentText = readRequired(metadata.getDocumentTextPath(), "项目尚未完成文档解析");
        String aiInput = readOptional(metadata.getRequirementDetailPath());
        List<ModuleBlock> sourceModules = findSourceModules(documentText);
        return buildResponse(sourceModules, aiInput, documentText.length());
    }

    public RequirementCoverageResponse includeModules(String projectId, List<String> moduleNumbers) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        String documentText = readRequired(metadata.getDocumentTextPath(), "项目尚未完成文档解析");
        Path requirementPath = StringUtils.hasText(metadata.getRequirementDetailPath())
                ? Paths.get(metadata.getRequirementDetailPath())
                : fileStorageService.resolveProjectDir(projectId).resolve("requirement-detail.txt");
        String aiInput = readOptional(requirementPath.toString());
        List<ModuleBlock> modules = findSourceModules(documentText);
        Set<String> selected = new LinkedHashSet<>(moduleNumbers == null ? Collections.<String>emptyList() : moduleNumbers);
        if (selected.isEmpty()) {
            throw new BusinessException("请选择需要补入 AI 输入的功能模块");
        }
        StringBuilder supplemented = new StringBuilder(aiInput == null ? "" : aiInput.trim());
        for (ModuleBlock module : modules) {
            if (selected.contains(module.number) && !containsModule(supplemented.toString(), module)) {
                supplemented.append(System.lineSeparator())
                        .append("【完整性校验补充原文模块】").append(System.lineSeparator())
                        .append(module.text.toString().trim()).append(System.lineSeparator());
            }
        }
        try {
            Files.write(requirementPath, supplemented.toString().trim().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BusinessException("写入补充后的 AI 输入失败", ex);
        }
        requirementInputService.markCustomized(projectId, metadata, supplemented.toString());
        return buildResponse(modules, supplemented.toString(), documentText.length());
    }

    public void ensureReadyForExtraction(String projectId) {
        RequirementCoverageResponse coverage = getCoverage(projectId);
        if (coverage.getSourceModuleCount() > 0 && coverage.getMissingModuleCount() > 0) {
            throw new BusinessException("解析完整性校验未通过：原文识别到 " + coverage.getSourceModuleCount()
                    + " 个功能模块，其中 " + coverage.getMissingModuleCount()
                    + " 个尚未进入 AI 输入。请先在“解析完整性校验”中补入缺失模块。");
        }
    }

    private RequirementCoverageResponse buildResponse(List<ModuleBlock> sourceModules, String aiInput, int sourceLength) {
        String input = aiInput == null ? "" : aiInput;
        RequirementCoverageResponse response = new RequirementCoverageResponse();
        response.setSourceModuleCount(sourceModules.size());
        response.setSourceLength(sourceLength);
        response.setAiInputLength(input.length());
        List<RequirementCoverageModule> moduleResponses = new ArrayList<>();
        int included = 0;
        for (ModuleBlock source : sourceModules) {
            boolean present = containsModule(input, source);
            RequirementCoverageModule module = new RequirementCoverageModule();
            module.setNumber(source.number);
            module.setTitle(source.title);
            module.setSourceLength(source.text.length());
            module.setIncluded(present);
            module.setIncludedLength(present ? source.text.length() : 0);
            module.setStatus(present ? "INCLUDED" : "MISSING");
            moduleResponses.add(module);
            if (present) {
                included++;
            }
        }
        response.setModules(moduleResponses);
        response.setIncludedModuleCount(included);
        response.setMissingModuleCount(sourceModules.size() - included);
        response.setReadyForExtraction(sourceModules.isEmpty() || included == sourceModules.size());
        return response;
    }

    private List<ModuleBlock> findSourceModules(String documentText) {
        String[] lines = documentText.split("\\r?\\n");
        boolean inBusinessSection = false;
        List<ModuleBlock> modules = new ArrayList<>();
        ModuleBlock current = null;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (!inBusinessSection) {
                if (BUSINESS_SECTION.matcher(line).matches()) {
                    inBusinessSection = true;
                }
                continue;
            }
            if (isBusinessSectionEndBoundary(line)) {
                break;
            }
            Matcher heading = MODULE_HEADING.matcher(line);
            if (heading.matches() && headingDepth(heading.group(1)) == 3 && !isSupplementalTitle(heading.group(2))) {
                if (current != null) {
                    modules.add(current);
                }
                current = new ModuleBlock(heading.group(1).replace('．', '.'), heading.group(2).trim());
            }
            if (current != null && StringUtils.hasText(line)) {
                current.text.append(line).append(System.lineSeparator());
            }
        }
        if (current != null) {
            modules.add(current);
        }
        return modules;
    }

    private boolean containsModule(String input, ModuleBlock module) {
        return input.contains(module.number + " " + module.title)
                || input.contains(module.number + "　" + module.title)
                || input.contains(module.number + " " + module.title.replace(" ", ""));
    }

    private int headingDepth(String number) {
        return number.split("[.．]").length;
    }

    private boolean isSupplementalTitle(String title) {
        return "功能描述".equals(title) || "功能界面".equals(title) || "流程图".equals(title);
    }

    private boolean isBusinessSectionEndBoundary(String line) {
        String normalized = normalizeTitle(line);
        if ("非功能性需求".equals(normalized)
                || "非功能需求".equals(normalized)
                || "调整因子情况说明".equals(normalized)
                || "附加值调整因子说明".equals(normalized)) {
            return true;
        }
        return line.matches("^\\d+[.．]?\\s+\\S.*$")
                && normalized.length() <= 60
                && !normalized.contains("：")
                && !normalized.contains(":");
    }

    private String normalizeTitle(String line) {
        if (!StringUtils.hasText(line)) {
            return "";
        }
        return line.trim()
                .replaceAll("\\s+", "")
                .replaceAll("^\\d+(?:[.．]\\d+)*[.．]?", "")
                .trim();
    }

    private String readRequired(String path, String message) {
        String text = readOptional(path);
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(message);
        }
        return text;
    }

    private String readOptional(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        try {
            Path resolved = Paths.get(path);
            return Files.exists(resolved) ? new String(Files.readAllBytes(resolved), StandardCharsets.UTF_8) : "";
        } catch (IOException ex) {
            throw new BusinessException("读取需求解析文件失败", ex);
        }
    }

    private static class ModuleBlock {
        private final String number;
        private final String title;
        private final StringBuilder text = new StringBuilder();

        private ModuleBlock(String number, String title) {
            this.number = number;
            this.title = title;
        }
    }
}
