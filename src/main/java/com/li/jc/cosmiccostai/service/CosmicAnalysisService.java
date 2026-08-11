package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.CosmicAnalyzeResponse;
import com.li.jc.cosmiccostai.dto.RuleValidationIssue;
import com.li.jc.cosmiccostai.model.CosmicMovement;
import com.li.jc.cosmiccostai.model.CosmicResult;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CosmicAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CosmicAnalysisService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileStorageService fileStorageService;
    private final AiGeneratedSplitQualityService aiGeneratedSplitQualityService;
    private final ObjectMapper objectMapper;
    private final Set<String> runningProjects = ConcurrentHashMap.newKeySet();

    @Autowired
    public CosmicAnalysisService(FileStorageService fileStorageService,
                                 AiGeneratedSplitQualityService aiGeneratedSplitQualityService) {
        this.fileStorageService = fileStorageService;
        this.aiGeneratedSplitQualityService = aiGeneratedSplitQualityService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public CosmicAnalysisService(FileStorageService fileStorageService) {
        this(fileStorageService, new AiGeneratedSplitQualityService(new RuleValidationService()));
    }

    public CosmicAnalyzeResponse analyze(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);
        if (!runningProjects.add(projectId)) {
            throw new BusinessException("COSMIC 拆分正在进行，请等待当前任务完成。");
        }
        try {
            updateProcessingStatus(metadata, "COSMIC_SPLITTING");
            return analyzeInternal(projectId, metadata);
        } catch (RuntimeException ex) {
            updateProcessingStatus(metadata, "COSMIC_FAILED");
            throw ex;
        } finally {
            runningProjects.remove(projectId);
        }
    }

    private CosmicAnalyzeResponse analyzeInternal(String projectId, ProjectMetadata metadata) {
        List<ExtractedFunction> functions = readExtractedFunctions(Paths.get(metadata.getExtractedFunctionsPath()));
        List<RuleValidationIssue> structureIssues = aiGeneratedSplitQualityService.validate(functions);
        if (!structureIssues.isEmpty()) {
            log.warn("COSMIC conversion keeps AI issues for manual initial review. "
                            + "projectId={}, issueCount={}, issues={}",
                    projectId, structureIssues.size(), aiGeneratedSplitQualityService.summarize(structureIssues));
        }
        List<CosmicResult> results = new ArrayList<CosmicResult>();
        double totalCfp = 0;
        for (ExtractedFunction function : functions) {
            CosmicResult result = analyzeFunction(function);
            results.add(result);
            totalCfp += result.getCfp();
        }

        Path resultPath = fileStorageService.resolveProjectDir(projectId).resolve("cosmic-results.json");
        writeResults(resultPath, results);

        metadata.setCosmicResultsPath(resultPath.toString());
        metadata.setStatus("ANALYZED");
        metadata.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
        fileStorageService.saveMetadata(metadata);

        CosmicAnalyzeResponse response = new CosmicAnalyzeResponse();
        response.setProjectId(projectId);
        response.setCosmicResultsPath(resultPath.toString());
        response.setFunctionCount(results.size());
        response.setTotalCfp(totalCfp);
        response.setStatus(metadata.getStatus());
        response.setResults(results);
        return response;
    }

    private void updateProcessingStatus(ProjectMetadata metadata, String status) {
        metadata.setStatus(status);
        metadata.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
        fileStorageService.saveMetadata(metadata);
    }

    private void validateMetadata(ProjectMetadata metadata) {
        if (metadata == null) {
            throw new BusinessException("Project metadata is empty");
        }
        if (!StringUtils.hasText(metadata.getExtractedFunctionsPath())) {
            throw new BusinessException("Project has not extracted functions yet");
        }
    }

    private List<ExtractedFunction> readExtractedFunctions(Path path) {
        if (!Files.exists(path)) {
            throw new BusinessException("Extracted functions file not found: " + path);
        }
        try {
            return objectMapper.readValue(path.toFile(), new TypeReference<List<ExtractedFunction>>() {
            });
        } catch (IOException ex) {
            throw new BusinessException("Read extracted functions failed: " + path, ex);
        }
    }

    CosmicResult analyzeFunction(ExtractedFunction function) {
        String text = safe(function.getFunction()) + " " + safe(function.getDescription());
        MovementDataAttributeNormalizer.normalize(function.getMovements());

        int entry;
        int read;
        int write;
        int exit;
        if (hasDetailedMovements(function.getMovements())) {
            entry = countMovements(function.getMovements(), "E");
            read = countMovements(function.getMovements(), "R");
            write = countMovements(function.getMovements(), "W");
            exit = countMovements(function.getMovements(), "X");
        } else {
            entry = 1;
            read = needRead(text) ? 1 : 0;
            write = needWrite(text) ? 1 : 0;
            exit = needExit(text) ? 1 : 0;
            ActionMovementRule.Match actionMatch = ActionMovementRule.resolve(
                    function.getFunction(), function.getTriggerEvent(),
                    safe(function.getDescription()) + " " + safe(function.getSequenceDiagram()));
            if (actionMatch != null) {
                ActionMovementRule.Profile profile = actionMatch.getProfile();
                if (profile.getRecommendedReadCount() != null) {
                    read = profile.getRecommendedReadCount();
                }
                if (profile.getRecommendedWriteCount() != null) {
                    write = profile.getRecommendedWriteCount();
                }
                if (profile.getRecommendedExitCount() != null) {
                    exit = profile.getRecommendedExitCount();
                }
                String evidence = safe(function.getFunction()) + " " + safe(function.getDescription());
                if ("导出".equals(profile.getLabel())) {
                    write = ActionMovementRule.hasPersistentExportEvidence(evidence) ? 1 : 0;
                } else if ("发送/推送".equals(profile.getLabel())) {
                    write = ActionMovementRule.hasMessageRecordEvidence(evidence) ? 1 : 0;
                } else if ("定时文件采集".equals(profile.getLabel())
                        && ActionMovementRule.hasCombinedCollectionEvidence(evidence)) {
                    write = 2;
                }
            }
        }

        if (write == 0 && exit == 0) {
            exit = 1;
        }

        CosmicResult result = new CosmicResult();
        result.setModule(function.getModule());
        result.setFunction(function.getFunction());
        result.setDescription(function.getDescription());
        result.setFunctionalUser(function.getFunctionalUser());
        result.setTriggerEvent(function.getTriggerEvent());
        result.setSequenceDiagram(function.getSequenceDiagram());
        result.setMovements(function.getMovements());
        result.setEvidence(function.getEvidence());
        result.setSourceReferences(function.getSourceReferences());
        result.setEntry(entry);
        result.setRead(read);
        result.setWrite(write);
        result.setExit(exit);
        result.setCfp(entry + read + write + exit);
        return result;
    }

    private boolean hasDetailedMovements(List<CosmicMovement> movements) {
        if (movements == null || movements.isEmpty()) {
            return false;
        }
        boolean hasEntry = false;
        boolean hasResult = false;
        for (CosmicMovement movement : movements) {
            String type = movement == null ? "" : safe(movement.getType()).toUpperCase(Locale.ROOT);
            if ("E".equals(type)) {
                hasEntry = true;
            }
            if ("W".equals(type) || "X".equals(type)) {
                hasResult = true;
            }
        }
        return hasEntry && hasResult;
    }

    private int countMovements(List<CosmicMovement> movements, String expectedType) {
        int count = 0;
        for (CosmicMovement movement : movements) {
            String type = movement == null ? "" : safe(movement.getType()).toUpperCase(Locale.ROOT);
            if (expectedType.equals(type)) {
                count++;
            }
        }
        return count;
    }

    private boolean needRead(String text) {
        return containsAny(text, "查看", "查询", "读取", "获取", "列表", "详情", "统计", "汇总",
                "导出", "下载", "预览", "审核", "修改", "追加", "配置");
    }

    private boolean needWrite(String text) {
        return containsAny(text, "新增", "新建", "创建", "添加", "删除", "移除", "废止", "作废", "注销",
                "导入", "录入", "发布", "修改", "编辑", "更新", "变更",
                "追加", "配置", "提交", "审核", "审批", "设置", "管理", "保存");
    }

    private boolean needExit(String text) {
        return containsAny(text, "查看", "查询", "返回", "展示", "列表", "详情", "统计", "汇总", "导出",
                "下载", "预览", "推送", "通知", "短信", "发送", "审核");
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
        return value == null ? "" : value;
    }

    private void writeResults(Path path, List<CosmicResult> results) {
        try {
            objectMapper.writeValue(path.toFile(), results);
        } catch (IOException ex) {
            throw new BusinessException("Write COSMIC results failed: " + path, ex);
        }
    }
}
