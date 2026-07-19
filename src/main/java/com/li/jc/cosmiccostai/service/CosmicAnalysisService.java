package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.CosmicAnalyzeResponse;
import com.li.jc.cosmiccostai.model.CosmicResult;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class CosmicAnalysisService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public CosmicAnalysisService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public CosmicAnalyzeResponse analyze(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);

        List<ExtractedFunction> functions = readExtractedFunctions(Paths.get(metadata.getExtractedFunctionsPath()));
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

    private CosmicResult analyzeFunction(ExtractedFunction function) {
        String text = safe(function.getFunction()) + " " + safe(function.getDescription());

        int entry = 1;
        int read = needRead(text) ? 1 : 0;
        int write = needWrite(text) ? 1 : 0;
        int exit = needExit(text) ? 1 : 0;

        if (write == 0 && exit == 0) {
            exit = 1;
        }

        CosmicResult result = new CosmicResult();
        result.setModule(function.getModule());
        result.setFunction(function.getFunction());
        result.setDescription(function.getDescription());
        result.setSequenceDiagram(function.getSequenceDiagram());
        result.setEntry(entry);
        result.setRead(read);
        result.setWrite(write);
        result.setExit(exit);
        result.setCfp(entry + read + write + exit);
        return result;
    }

    private boolean needRead(String text) {
        return containsAny(text, "查看", "查询", "读取", "获取", "列表", "详情", "审核", "修改", "删除", "废止", "追加", "配置");
    }

    private boolean needWrite(String text) {
        return containsAny(text, "新增", "创建", "发布", "修改", "删除", "废止", "追加", "配置", "提交", "审核", "设置", "管理", "保存", "更新", "发送");
    }

    private boolean needExit(String text) {
        return containsAny(text, "查看", "查询", "返回", "展示", "推送", "通知", "短信", "发送", "审核");
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
