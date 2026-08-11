package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.ReviewResponse;
import com.li.jc.cosmiccostai.model.CosmicMovement;
import com.li.jc.cosmiccostai.model.CosmicResult;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileStorageService fileStorageService;
    private final FunctionDescriptionAiService functionDescriptionAiService;
    private final RuleValidationService ruleValidationService;
    private final ObjectMapper objectMapper;

    public ReviewService(FileStorageService fileStorageService) {
        this(fileStorageService, new FunctionDescriptionAiService(), new RuleValidationService());
    }

    public ReviewService(FileStorageService fileStorageService, FunctionDescriptionAiService functionDescriptionAiService) {
        this(fileStorageService, functionDescriptionAiService, new RuleValidationService());
    }

    @Autowired
    public ReviewService(FileStorageService fileStorageService,
                         FunctionDescriptionAiService functionDescriptionAiService,
                         RuleValidationService ruleValidationService) {
        this.fileStorageService = fileStorageService;
        this.functionDescriptionAiService = functionDescriptionAiService;
        this.ruleValidationService = ruleValidationService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public ReviewResponse getReview(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);
        List<FunctionSplitRow> rows = readOrCreateSplitRows(metadata);
        List<CosmicResult> results = toCosmicResults(rows);
        return buildResponse(metadata, results, rows);
    }

    public ReviewResponse saveReview(String projectId, List<FunctionSplitRow> rows) {
        return saveReview(projectId, rows, false);
    }

    public ReviewResponse saveReview(String projectId, List<FunctionSplitRow> rows, boolean polishFunctionDescription) {
        return saveReview(projectId, rows, "REVIEWED", false);
    }

    public ReviewResponse importReview(String projectId, List<FunctionSplitRow> rows) {
        return importReview(projectId, rows, false);
    }

    public ReviewResponse importReview(String projectId, List<FunctionSplitRow> rows, boolean polishFunctionDescription) {
        return saveReview(projectId, rows, "EXCEL_IMPORTED", false);
    }

    public ReviewResponse saveReuseAdjustedReview(String projectId, List<FunctionSplitRow> rows) {
        return saveReview(projectId, rows, "REUSE_ADJUSTED", false);
    }

    private ReviewResponse saveReview(String projectId, List<FunctionSplitRow> rows, String status) {
        return saveReview(projectId, rows, status, false);
    }

    private ReviewResponse saveReview(String projectId, List<FunctionSplitRow> rows, String status, boolean polishFunctionDescription) {
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException("Review split rows cannot be empty");
        }
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);

        long startedAt = System.currentTimeMillis();
        log.info("Saving review rows. projectId={}, status={}, rows={}, polishFunctionDescription={}",
                projectId, status, rows.size(), polishFunctionDescription);
        normalizeRows(metadata, rows, polishFunctionDescription);
        RequirementSourceOrder.sortRows(rows, readExtractedFunctions(metadata));
        if (!"EXCEL_IMPORTED".equals(status) && !"REUSE_ADJUSTED".equals(status)
                && "DOCUMENT".equals(sourceType(metadata))) {
            ruleValidationService.ensureNoErrors(rows, "保存审核结果");
        }
        List<CosmicResult> results = toCosmicResults(rows);

        Path projectDir = fileStorageService.resolveProjectDir(projectId);
        Path splitPath = projectDir.resolve("function-split-rows.json");
        writeJson(splitPath, rows);
        writeJson(Paths.get(metadata.getCosmicResultsPath()), results);

        metadata.setFunctionSplitRowsPath(splitPath.toString());
        metadata.setRuleVersion(ruleValidationService.getRuleVersion());
        metadata.setStatus(status);
        metadata.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
        fileStorageService.saveMetadata(metadata);
        log.info("Saved review rows. projectId={}, status={}, rows={}, elapsedMs={}",
                projectId, status, rows.size(), System.currentTimeMillis() - startedAt);
        return buildResponse(metadata, results, rows);
    }

    private List<FunctionSplitRow> readOrCreateSplitRows(ProjectMetadata metadata) {
        if (StringUtils.hasText(metadata.getFunctionSplitRowsPath()) && Files.exists(Paths.get(metadata.getFunctionSplitRowsPath()))) {
            try {
                List<FunctionSplitRow> rows = objectMapper.readValue(Paths.get(metadata.getFunctionSplitRowsPath()).toFile(),
                        new TypeReference<List<FunctionSplitRow>>() {
                        });
                normalizeRows(metadata, rows, false);
                RequirementSourceOrder.sortRows(rows, readExtractedFunctions(metadata));
                return rows;
            } catch (IOException ex) {
                throw new BusinessException("Read function split rows failed", ex);
            }
        }

        List<CosmicResult> results = readCosmicResults(Paths.get(metadata.getCosmicResultsPath()));
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        for (CosmicResult result : results) {
            rows.addAll(toSplitRows(metadata, result));
        }
        normalizeRows(metadata, rows, false);
        RequirementSourceOrder.sortRows(rows, readExtractedFunctions(metadata));
        return rows;
    }

    private List<ExtractedFunction> readExtractedFunctions(ProjectMetadata metadata) {
        if (metadata == null || !StringUtils.hasText(metadata.getExtractedFunctionsPath())) {
            return new ArrayList<ExtractedFunction>();
        }
        Path path = Paths.get(metadata.getExtractedFunctionsPath());
        if (!Files.exists(path)) {
            return new ArrayList<ExtractedFunction>();
        }
        try {
            return objectMapper.readValue(path.toFile(), new TypeReference<List<ExtractedFunction>>() {
            });
        } catch (IOException ex) {
            throw new BusinessException("Read extracted functions failed: " + path, ex);
        }
    }

    private List<FunctionSplitRow> toSplitRows(ProjectMetadata metadata, CosmicResult result) {
        return FunctionSplitRowFactory.create(metadata, result);
    }

    private void normalizeRows(ProjectMetadata metadata, List<FunctionSplitRow> rows, boolean polishFunctionDescription) {
        String lastLevel1 = "";
        String lastLevel2 = "";
        String lastLevel3 = "";
        String lastProcess = "";
        for (FunctionSplitRow row : rows) {
            if (!StringUtils.hasText(row.getProjectName())) {
                row.setProjectName(metadata.getName());
            }
            if (!StringUtils.hasText(row.getLevel1Module())) {
                row.setLevel1Module(lastLevel1);
            }
            if (!StringUtils.hasText(row.getLevel1Module())) {
                row.setLevel1Module(zh("\u672a\u5206\u7c7b\u6a21\u5757"));
            }
            if (!StringUtils.hasText(row.getLevel2Module())) {
                row.setLevel2Module(row.getLevel1Module());
            }
            if (!StringUtils.hasText(row.getLevel3Module())) {
                row.setLevel3Module(row.getLevel2Module());
            }
            if (!StringUtils.hasText(row.getFunctionProcess())) {
                row.setFunctionProcess(StringUtils.hasText(lastProcess) ? lastProcess : row.getLevel3Module());
            }
            if (!StringUtils.hasText(row.getSubProcessDescription())) {
                row.setSubProcessDescription(row.getFunctionProcess());
            }
            if (shouldRegenerateFunctionDescription(row)) {
                row.setFunctionDescription(buildFunctionDescription(null, row.getSubProcessDescription(), row.getDataAttributes()));
            }
            row.setMovementType(normalizeMovementType(row.getMovementType()));
            if (!StringUtils.hasText(row.getFunctionalUser())) {
                row.setFunctionalUser(zh("\u53d1\u8d77\u8005\uff1a\u7528\u6237\n\u63a5\u6536\u8005\uff1a\u7cfb\u7edf"));
            }
            if (!StringUtils.hasText(row.getTriggerEvent())) {
                row.setTriggerEvent(row.getFunctionProcess());
            }
            if (!StringUtils.hasText(row.getDataGroup())) {
                row.setDataGroup(zh("\u5f85\u786e\u8ba4"));
            }
            if (!StringUtils.hasText(row.getDataAttributes())) {
                row.setDataAttributes(zh("\u5f85\u786e\u8ba4"));
            }
            if (!StringUtils.hasText(row.getSequenceDiagram())) {
                row.setSequenceDiagram(defaultSequenceDiagram(row.getMovementType(), row.getSubProcessDescription(),
                        row.getDataGroup(), row.getDataAttributes()));
            }
            MovementDescriptionNormalizer.normalize(row);
            row.setReuseDegree(normalizeReuseDegree(row.getReuseDegree()));
            row.setCfp(cfpByReuse(row.getReuseDegree()));
            lastLevel1 = row.getLevel1Module();
            lastLevel2 = row.getLevel2Module();
            lastLevel3 = row.getLevel3Module();
            lastProcess = row.getFunctionProcess();
        }
        if (polishFunctionDescription) {
            applyGroupFunctionDescriptions(rows);
        } else {
            log.info("Skip AI function description polish. projectId={}, rows={}", metadata.getId(), rows.size());
        }
    }

    private String normalizeMovementType(String movementType) {
        String value = movementType == null ? "" : movementType.trim().toUpperCase(Locale.ROOT);
        if ("E".equals(value) || "R".equals(value) || "W".equals(value) || "X".equals(value)) {
            return value;
        }
        return "E";
    }

    private String buildFunctionDescription(String requirementDescription, String subProcessDescription, String dataAttributes) {
        String subprocess = safe(subProcessDescription);
        String attributes = safe(dataAttributes);
        String requirement = safe(requirementDescription);
        if (StringUtils.hasText(requirement)) {
            return requirement + zh("\u5176\u4e2d\uff0c") + subprocess + zh("\uff0c\u6d89\u53ca") + attributes + zh("\u3002");
        }
        return subprocess + zh("\uff0c\u6d89\u53ca") + attributes + zh("\u3002");
    }

    private boolean shouldRegenerateFunctionDescription(FunctionSplitRow row) {
        String value = safe(row.getFunctionDescription());
        if (!StringUtils.hasText(value)) {
            return true;
        }
        if ("__AI_FUNCTION_DESCRIPTION_PENDING__".equals(value)) {
            return true;
        }
        return value.equals(safe(row.getFunctionProcess())) || value.equals(safe(row.getSubProcessDescription()));
    }

    private void applyGroupFunctionDescriptions(List<FunctionSplitRow> rows) {
        Map<String, List<FunctionSplitRow>> groups = new LinkedHashMap<String, List<FunctionSplitRow>>();
        for (FunctionSplitRow row : rows) {
            String key = FunctionProcessKey.of(row);
            List<FunctionSplitRow> groupRows = groups.get(key);
            if (groupRows == null) {
                groupRows = new ArrayList<FunctionSplitRow>();
                groups.put(key, groupRows);
            }
            groupRows.add(row);
        }
        Map<String, List<FunctionSplitRow>> pendingGroups = new LinkedHashMap<String, List<FunctionSplitRow>>();
        for (Map.Entry<String, List<FunctionSplitRow>> entry : groups.entrySet()) {
            if (needsFunctionDescriptionRefresh(entry.getValue())) {
                pendingGroups.put(entry.getKey(), entry.getValue());
            }
        }
        Map<String, String> aiDescriptions = functionDescriptionAiService.generateDescriptions(pendingGroups);
        for (Map.Entry<String, List<FunctionSplitRow>> entry : groups.entrySet()) {
            List<FunctionSplitRow> groupRows = entry.getValue();
            String groupDescription = aiDescriptions.get(entry.getKey());
            if (!StringUtils.hasText(groupDescription)) {
                groupDescription = buildGroupFunctionDescription(groupRows);
            }
            for (FunctionSplitRow row : groupRows) {
                if (shouldRegenerateFunctionDescription(row) || isLegacyGeneratedFunctionDescription(row)) {
                    row.setFunctionDescription(groupDescription);
                }
            }
        }
    }

    private boolean needsFunctionDescriptionRefresh(List<FunctionSplitRow> rows) {
        for (FunctionSplitRow row : rows) {
            if (shouldRegenerateFunctionDescription(row) || isLegacyGeneratedFunctionDescription(row)) {
                return true;
            }
        }
        return false;
    }

    private String buildGroupFunctionDescription(List<FunctionSplitRow> rows) {
        FunctionSplitRow first = rows.get(0);
        String process = safe(first.getFunctionProcess());
        List<String> actions = new ArrayList<String>();
        Set<String> dataGroups = new LinkedHashSet<String>();
        Set<String> attributes = new LinkedHashSet<String>();
        for (FunctionSplitRow row : rows) {
            addUnique(actions, safe(row.getSubProcessDescription()));
            addUnique(dataGroups, safe(row.getDataGroup()));
            for (String attribute : splitAttributes(row.getDataAttributes())) {
                addUnique(attributes, attribute);
            }
        }
        StringBuilder builder = new StringBuilder();
        builder.append(zh("\u672c\u529f\u80fd\u7528\u4e8e\u5b8c\u6210")).append(process).append(zh("\uff0c"));
        builder.append(zh("\u7cfb\u7edf\u6309\u7167")).append(joinChinese(actions, zh("\u3001"))).append(zh("\u7684\u5904\u7406\u6d41\u7a0b"));
        if (!dataGroups.isEmpty()) {
            builder.append(zh("\uff0c\u56f4\u7ed5")).append(joinChinese(new ArrayList<String>(dataGroups), zh("\u3001"))).append(zh("\u7b49\u6570\u636e\u7ec4"));
        }
        if (!attributes.isEmpty()) {
            builder.append(zh("\uff0c\u5904\u7406")).append(joinChinese(limit(new ArrayList<String>(attributes), 12), zh("\u3001"))).append(zh("\u7b49\u4e1a\u52a1\u5c5e\u6027"));
        }
        builder.append(zh("\uff0c\u5f62\u6210\u8be5\u529f\u80fd\u7684\u8f93\u5165\u3001\u8bfb\u53d6\u3001\u5199\u5165\u6216\u8f93\u51fa\u7ed3\u679c\u3002"));
        return builder.toString();
    }

    private boolean isLegacyGeneratedFunctionDescription(FunctionSplitRow row) {
        String value = safe(row.getFunctionDescription());
        if (!StringUtils.hasText(value)) {
            return true;
        }
        if ("__AI_FUNCTION_DESCRIPTION_PENDING__".equals(value)) {
            return true;
        }
        String subProcess = safe(row.getSubProcessDescription());
        String attributes = safe(row.getDataAttributes());
        return value.equals(buildFunctionDescription(null, subProcess, attributes))
                || (value.contains(zh("\u5176\u4e2d\uff0c") + subProcess) && value.endsWith(zh("\uff0c\u6d89\u53ca") + attributes + zh("\u3002")))
                || (value.startsWith(zh("\u672c\u529f\u80fd\u7528\u4e8e\u5b8c\u6210")) && value.contains(zh("\u652f\u6491\u8be5\u529f\u80fd\u7684\u8f93\u5165")));
    }

    private List<String> splitAttributes(String value) {
        List<String> result = new ArrayList<String>();
        String text = safe(value).replace('\uff0c', ',').replace('\u3001', ',').replace(';', ',').replace('\uff1b', ',');
        for (String part : text.split(",")) {
            addUnique(result, part.trim());
        }
        return result;
    }

    private List<String> limit(List<String> values, int max) {
        if (values.size() <= max) {
            return values;
        }
        return new ArrayList<String>(values.subList(0, max));
    }

    private void addUnique(List<String> values, String value) {
        if (StringUtils.hasText(value) && !values.contains(value)) {
            values.add(value);
        }
    }

    private void addUnique(Set<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(value);
        }
    }

    private String joinChinese(List<String> values, String delimiter) {
        if (values == null || values.isEmpty()) {
            return zh("\u4e1a\u52a1\u6570\u636e\u5904\u7406");
        }
        return String.join(delimiter, values);
    }

    private String defaultSequenceDiagram(String movementType, String subProcessDescription, String dataGroup, String dataAttributes) {
        String type = normalizeMovementType(movementType);
        if ("E".equals(type)) {
            return "UI->UI";
        }
        if ("R".equals(type)) {
            if (isBackendRead(subProcessDescription, dataGroup, dataAttributes)) {
                return zh("\u540e\u53f0\u670d\u52a1->\u540e\u53f0\u670d\u52a1");
            }
            return zh("UI->\u540e\u53f0\u670d\u52a1");
        }
        if ("W".equals(type)) {
            return zh("\u540e\u53f0\u670d\u52a1->\u540e\u53f0\u670d\u52a1");
        }
        return zh("\u540e\u53f0\u670d\u52a1->UI");
    }

    private boolean isBackendRead(String subProcessDescription, String dataGroup, String dataAttributes) {
        String text = safe(subProcessDescription) + " " + safe(dataGroup) + " " + safe(dataAttributes);
        return containsAny(text, zh("\u6570\u636e\u5904\u7406"), zh("\u8ba1\u7b97"), zh("\u6c47\u603b"), zh("\u7edf\u8ba1"),
                zh("\u6821\u9a8c"), zh("\u8f6c\u6362"), zh("\u4fdd\u5b58"), zh("\u5199\u5165"), zh("\u66f4\u65b0"));
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

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private double cfpByReuse(String reuseDegree) {
        if (zh("\u590d\u7528").equals(reuseDegree)) {
            return 1.0 / 3.0;
        }
        if (zh("\u5229\u65e7").equals(reuseDegree)) {
            return 0;
        }
        return 1;
    }

    private String normalizeReuseDegree(String reuseDegree) {
        String value = reuseDegree == null ? "" : reuseDegree.trim();
        if (zh("\u65b0\u589e").equals(value) || zh("\u590d\u7528").equals(value) || zh("\u5229\u65e7").equals(value)) {
            return value;
        }
        return zh("\u65b0\u589e");
    }

    private List<CosmicResult> toCosmicResults(List<FunctionSplitRow> rows) {
        Map<String, CosmicResult> map = new LinkedHashMap<String, CosmicResult>();
        for (FunctionSplitRow row : rows) {
            String process = row.getFunctionProcess();
            CosmicResult result = map.get(process);
            if (result == null) {
                result = new CosmicResult();
                result.setModule(row.getLevel1Module());
                result.setFunction(process);
                result.setDescription(row.getFunctionDescription());
                result.setFunctionalUser(row.getFunctionalUser());
                result.setTriggerEvent(row.getTriggerEvent());
                result.setSequenceDiagram(row.getSequenceDiagram());
                result.setMovements(new ArrayList<CosmicMovement>());
                map.put(process, result);
            }
            CosmicMovement movement = new CosmicMovement();
            movement.setType(row.getMovementType());
            movement.setDescription(row.getSubProcessDescription());
            movement.setDataGroup(row.getDataGroup());
            movement.setDataAttributes(row.getDataAttributes());
            movement.setSequenceDiagram(row.getSequenceDiagram());
            result.getMovements().add(movement);
            if ("E".equals(row.getMovementType())) {
                result.setEntry(result.getEntry() + 1);
            } else if ("R".equals(row.getMovementType())) {
                result.setRead(result.getRead() + 1);
            } else if ("W".equals(row.getMovementType())) {
                result.setWrite(result.getWrite() + 1);
            } else if ("X".equals(row.getMovementType())) {
                result.setExit(result.getExit() + 1);
            }
            result.setCfp(result.getCfp() + row.getCfp());
        }
        return new ArrayList<CosmicResult>(map.values());
    }

    private List<CosmicResult> readCosmicResults(Path path) {
        if (!Files.exists(path)) {
            throw new BusinessException("COSMIC results file not found: " + path);
        }
        try {
            return objectMapper.readValue(path.toFile(), new TypeReference<List<CosmicResult>>() {
            });
        } catch (IOException ex) {
            throw new BusinessException("Read review results failed", ex);
        }
    }

    private void validateMetadata(ProjectMetadata metadata) {
        if (metadata == null || !StringUtils.hasText(metadata.getCosmicResultsPath())) {
            throw new BusinessException("Project has not completed COSMIC analysis yet");
        }
    }

    private void writeJson(Path path, Object value) {
        try {
            objectMapper.writeValue(path.toFile(), value);
        } catch (IOException ex) {
            throw new BusinessException("Save review results failed", ex);
        }
    }

    private ReviewResponse buildResponse(ProjectMetadata metadata, List<CosmicResult> results, List<FunctionSplitRow> rows) {
        double totalCfp = 0;
        for (CosmicResult result : results) {
            totalCfp += result.getCfp();
        }
        ReviewResponse response = new ReviewResponse();
        response.setProjectId(metadata.getId());
        response.setFunctionCount(results.size());
        response.setTotalCfp(totalCfp);
        response.setStatus(metadata.getStatus());
        response.setSourceType(sourceType(metadata));
        // Always expose the rule package that is currently judging this response.
        // Historical projects may not have persisted a rule version when they were created.
        response.setRuleVersion(ruleValidationService.getRuleVersion());
        response.setResults(results);
        response.setSplitRows(rows);
        return response;
    }

    private String sourceType(ProjectMetadata metadata) {
        String status = safe(metadata.getStatus()).toLowerCase(Locale.ROOT);
        String fileType = safe(metadata.getFileType()).toLowerCase(Locale.ROOT);
        String storedPath = safe(metadata.getStoredFilePath()).toLowerCase(Locale.ROOT);
        String sourceFileName = safe(metadata.getSourceFileName()).toLowerCase(Locale.ROOT);
        if (status.startsWith("excel")
                || "xls".equals(fileType)
                || "xlsx".equals(fileType)
                || storedPath.contains("split-review")
                || sourceFileName.endsWith(".xls")
                || sourceFileName.endsWith(".xlsx")) {
            return "EXCEL";
        }
        return "DOCUMENT";
    }

    private String zh(String value) {
        return value;
    }
}
