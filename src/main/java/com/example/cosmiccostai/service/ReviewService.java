package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.ReviewResponse;
import com.example.cosmiccostai.model.CosmicResult;
import com.example.cosmiccostai.model.FunctionSplitRow;
import com.example.cosmiccostai.model.ProjectMetadata;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ReviewService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public ReviewService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public ReviewResponse getReview(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);
        List<FunctionSplitRow> rows = readOrCreateSplitRows(metadata);
        List<CosmicResult> results = toCosmicResults(rows);
        return buildResponse(projectId, metadata.getStatus(), results, rows);
    }

    public ReviewResponse saveReview(String projectId, List<FunctionSplitRow> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException("Review split rows cannot be empty");
        }
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);

        normalizeRows(metadata, rows);
        List<CosmicResult> results = toCosmicResults(rows);

        Path projectDir = fileStorageService.resolveProjectDir(projectId);
        Path splitPath = projectDir.resolve("function-split-rows.json");
        writeJson(splitPath, rows);
        writeJson(Paths.get(metadata.getCosmicResultsPath()), results);

        metadata.setFunctionSplitRowsPath(splitPath.toString());
        metadata.setStatus("REVIEWED");
        metadata.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
        fileStorageService.saveMetadata(metadata);
        return buildResponse(projectId, metadata.getStatus(), results, rows);
    }

    private List<FunctionSplitRow> readOrCreateSplitRows(ProjectMetadata metadata) {
        if (StringUtils.hasText(metadata.getFunctionSplitRowsPath()) && Files.exists(Paths.get(metadata.getFunctionSplitRowsPath()))) {
            try {
                List<FunctionSplitRow> rows = objectMapper.readValue(Paths.get(metadata.getFunctionSplitRowsPath()).toFile(),
                        new TypeReference<List<FunctionSplitRow>>() {
                        });
                normalizeRows(metadata, rows);
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
        normalizeRows(metadata, rows);
        return rows;
    }

    private List<FunctionSplitRow> toSplitRows(ProjectMetadata metadata, CosmicResult result) {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        addRows(rows, metadata, result, "E", result.getEntry(), zh("\u63a5\u6536") + result.getFunction() + zh("\u8bf7\u6c42"),
                result.getFunction() + zh("\u8bf7\u6c42"), zh("\u8bf7\u6c42\u7f16\u53f7\u3001\u64cd\u4f5c\u4eba\u3001\u8bf7\u6c42\u65f6\u95f4"));
        addRows(rows, metadata, result, "R", result.getRead(), zh("\u8bfb\u53d6") + result.getFunction() + zh("\u76f8\u5173\u6570\u636e"),
                result.getFunction() + zh("\u6570\u636e"), zh("\u4e1a\u52a1\u7f16\u53f7\u3001\u4e1a\u52a1\u72b6\u6001\u3001\u4e1a\u52a1\u5185\u5bb9"));
        addRows(rows, metadata, result, "W", result.getWrite(), zh("\u4fdd\u5b58") + result.getFunction() + zh("\u5904\u7406\u7ed3\u679c"),
                result.getFunction() + zh("\u8bb0\u5f55"), zh("\u8bb0\u5f55\u7f16\u53f7\u3001\u5904\u7406\u72b6\u6001\u3001\u5904\u7406\u65f6\u95f4"));
        addRows(rows, metadata, result, "X", result.getExit(), zh("\u8fd4\u56de") + result.getFunction() + zh("\u5904\u7406\u7ed3\u679c"),
                result.getFunction() + zh("\u7ed3\u679c"), zh("\u7ed3\u679c\u7f16\u53f7\u3001\u7ed3\u679c\u72b6\u6001\u3001\u63d0\u793a\u4fe1\u606f"));
        return rows;
    }

    private void addRows(List<FunctionSplitRow> rows, ProjectMetadata metadata, CosmicResult result, String type, int count,
                         String description, String dataGroup, String dataAttributes) {
        for (int i = 0; i < count; i++) {
            FunctionSplitRow row = new FunctionSplitRow();
            row.setProjectName(metadata.getName());
            row.setLevel1Module(result.getModule());
            row.setLevel2Module(result.getModule());
            row.setLevel3Module(result.getModule());
            row.setFunctionalUser(zh("\u53d1\u8d77\u8005\uff1a\u7528\u6237\n\u63a5\u6536\u8005\uff1a\u7cfb\u7edf"));
            row.setTriggerEvent(result.getFunction());
            row.setFunctionProcess(result.getFunction());
            row.setSubProcessDescription(description);
            row.setMovementType(type);
            row.setDataGroup(dataGroup);
            row.setDataAttributes(dataAttributes);
            row.setReuseDegree(zh("\u65b0\u589e"));
            row.setCfp(1);
            rows.add(row);
        }
    }

    private void normalizeRows(ProjectMetadata metadata, List<FunctionSplitRow> rows) {
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
            row.setMovementType(normalizeMovementType(row.getMovementType()));
            if (!StringUtils.hasText(row.getFunctionalUser())) {
                row.setFunctionalUser(zh("\u53d1\u8d77\u8005\uff1a\u7528\u6237\n\u63a5\u6536\u8005\uff1a\u7cfb\u7edf"));
            }
            if (!StringUtils.hasText(row.getTriggerEvent())) {
                row.setTriggerEvent(row.getFunctionProcess());
            }
            if (!StringUtils.hasText(row.getDataGroup())) {
                row.setDataGroup(row.getFunctionProcess());
            }
            if (!StringUtils.hasText(row.getDataAttributes())) {
                row.setDataAttributes(zh("\u7f16\u53f7\u3001\u72b6\u6001\u3001\u65f6\u95f4"));
            }
            row.setReuseDegree(normalizeReuseDegree(row.getReuseDegree()));
            row.setCfp(cfpByReuse(row.getReuseDegree()));
            lastLevel1 = row.getLevel1Module();
            lastLevel2 = row.getLevel2Module();
            lastLevel3 = row.getLevel3Module();
            lastProcess = row.getFunctionProcess();
        }
    }

    private String normalizeMovementType(String movementType) {
        String value = movementType == null ? "" : movementType.trim().toUpperCase(Locale.ROOT);
        if ("E".equals(value) || "R".equals(value) || "W".equals(value) || "X".equals(value)) {
            return value;
        }
        return "E";
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
                result.setDescription(row.getSubProcessDescription());
                map.put(process, result);
            }
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

    private ReviewResponse buildResponse(String projectId, String status, List<CosmicResult> results, List<FunctionSplitRow> rows) {
        double totalCfp = 0;
        for (CosmicResult result : results) {
            totalCfp += result.getCfp();
        }
        ReviewResponse response = new ReviewResponse();
        response.setProjectId(projectId);
        response.setFunctionCount(results.size());
        response.setTotalCfp(totalCfp);
        response.setStatus(status);
        response.setResults(results);
        response.setSplitRows(rows);
        return response;
    }

    private String zh(String value) {
        return value;
    }
}
