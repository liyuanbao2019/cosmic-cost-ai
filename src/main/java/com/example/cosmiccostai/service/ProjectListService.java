package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.ProjectListItem;
import com.example.cosmiccostai.dto.ProjectListResponse;
import com.example.cosmiccostai.dto.RuleValidationResponse;
import com.example.cosmiccostai.model.FunctionSplitRow;
import com.example.cosmiccostai.model.ProjectMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class ProjectListService {

    private final Path projectsDir;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuleValidationService ruleValidationService;

    public ProjectListService(@Value("${cosmic.storage.base-dir:storage}") String storageBaseDir,
                              RuleValidationService ruleValidationService) {
        this.projectsDir = Paths.get(storageBaseDir).resolve("projects");
        this.ruleValidationService = ruleValidationService;
    }

    public ProjectListResponse listProjects() {
        List<ProjectListItem> items = new ArrayList<ProjectListItem>();
        if (!Files.exists(projectsDir)) {
            return response(items);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectsDir)) {
            for (Path projectDir : stream) {
                if (Files.isDirectory(projectDir)) {
                    ProjectListItem item = readProject(projectDir);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
        } catch (IOException ex) {
            return response(items);
        }
        Collections.sort(items, new Comparator<ProjectListItem>() {
            @Override
            public int compare(ProjectListItem left, ProjectListItem right) {
                return safe(right.getUpdatedAt()).compareTo(safe(left.getUpdatedAt()));
            }
        });
        return response(items);
    }

    public void deleteProject(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            throw new BusinessException("项目ID不能为空");
        }
        Path baseDir = projectsDir.toAbsolutePath().normalize();
        Path projectDir = projectsDir.resolve(projectId).toAbsolutePath().normalize();
        if (!projectDir.startsWith(baseDir) || projectDir.equals(baseDir)) {
            throw new BusinessException("项目ID非法，已拒绝删除");
        }
        if (!Files.exists(projectDir)) {
            throw new BusinessException("项目不存在：" + projectId);
        }
        try (Stream<Path> paths = Files.walk(projectDir)) {
            List<Path> deletePaths = new ArrayList<Path>();
            paths.forEach(deletePaths::add);
            Collections.sort(deletePaths, new Comparator<Path>() {
                @Override
                public int compare(Path left, Path right) {
                    return right.compareTo(left);
                }
            });
            for (Path path : deletePaths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            throw new BusinessException("删除项目失败：" + projectId, ex);
        }
    }

    public int deleteProjects(List<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            throw new BusinessException("请先选择要删除的项目");
        }
        Set<String> uniqueIds = new LinkedHashSet<String>();
        for (String projectId : projectIds) {
            if (StringUtils.hasText(projectId)) {
                uniqueIds.add(projectId.trim());
            }
        }
        if (uniqueIds.isEmpty()) {
            throw new BusinessException("请先选择要删除的项目");
        }
        for (String projectId : uniqueIds) {
            deleteProject(projectId);
        }
        return uniqueIds.size();
    }

    private ProjectListResponse response(List<ProjectListItem> items) {
        ProjectListResponse response = new ProjectListResponse();
        response.setProjects(items);
        response.setTotal(items.size());
        return response;
    }

    private ProjectListItem readProject(Path projectDir) {
        Path metadataPath = projectDir.resolve("metadata.json");
        if (!Files.exists(metadataPath)) {
            return null;
        }
        try {
            ProjectMetadata metadata = objectMapper.readValue(metadataPath.toFile(), ProjectMetadata.class);
            ProjectListItem item = new ProjectListItem();
            item.setProjectId(metadata.getId());
            item.setProjectName(metadata.getName());
            item.setSourceFileName(metadata.getSourceFileName());
            item.setSourceType(sourceType(metadata));
            item.setStatus(metadata.getStatus());
            item.setCreatedAt(metadata.getCreatedAt());
            item.setUpdatedAt(metadata.getUpdatedAt());
            item.setExcelExported(fileExists(metadata.getExcelOutputPath()));
            item.setBusinessExported(fileExists(metadata.getBusinessRequirementOutputPath()));
            item.setWordExported(fileExists(metadata.getWordOutputPath()));
            item.setExportStatus(exportStatus(item));
            enrichValidationSummary(item, metadata);
            return item;
        } catch (IOException ex) {
            return null;
        }
    }

    private void enrichValidationSummary(ProjectListItem item, ProjectMetadata metadata) {
        List<FunctionSplitRow> rows = readRows(metadata.getFunctionSplitRowsPath());
        if (rows.isEmpty()) {
            item.setFunctionCount(0);
            return;
        }
        Set<String> processes = new LinkedHashSet<String>();
        for (FunctionSplitRow row : rows) {
            if (StringUtils.hasText(row.getFunctionProcess())) {
                processes.add(row.getFunctionProcess().trim());
            }
        }
        item.setFunctionCount(processes.size());
        RuleValidationResponse validation = ruleValidationService.validate(rows);
        item.setIssueCount(validation.getIssueCount());
        item.setErrorCount(validation.getErrorCount());
        item.setRiskCount(validation.getRiskCount());
        item.setSuggestionCount(validation.getSuggestionCount());
    }

    private List<FunctionSplitRow> readRows(String path) {
        if (!StringUtils.hasText(path) || !Files.exists(Paths.get(path))) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(Paths.get(path).toFile(), new TypeReference<List<FunctionSplitRow>>() {
            });
        } catch (IOException ex) {
            return Collections.emptyList();
        }
    }

    private String sourceType(ProjectMetadata metadata) {
        String status = safe(metadata.getStatus());
        String storedPath = safe(metadata.getStoredFilePath());
        if (status.startsWith("EXCEL") || storedPath.contains("split-review")) {
            return "EXCEL";
        }
        return "DOCUMENT";
    }

    private boolean fileExists(String path) {
        return StringUtils.hasText(path) && Files.exists(Paths.get(path));
    }

    private String exportStatus(ProjectListItem item) {
        if (item.isExcelExported() && item.isBusinessExported() && item.isWordExported()) {
            return "全部已导出";
        }
        if (item.isExcelExported() || item.isBusinessExported() || item.isWordExported()) {
            return "部分已导出";
        }
        return "未导出";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
