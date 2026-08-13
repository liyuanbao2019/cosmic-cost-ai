package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.ProjectListItem;
import com.li.jc.cosmiccostai.dto.ProjectListResponse;
import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

@Service
public class ProjectListService {

    private final Path projectsDir;
    private final Path batchBaseDir;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuleValidationService ruleValidationService;
    private final ConcurrentMap<String, CachedMetadata> metadataCache = new ConcurrentHashMap<String, CachedMetadata>();
    private final ConcurrentMap<String, CachedSummary> summaryCache = new ConcurrentHashMap<String, CachedSummary>();
    private final ConcurrentMap<String, CachedBatch> batchCache = new ConcurrentHashMap<String, CachedBatch>();

    public ProjectListService(@Value("${cosmic.storage.base-dir:storage}") String storageBaseDir,
                              RuleValidationService ruleValidationService) {
        Path storage = Paths.get(storageBaseDir);
        this.projectsDir = storage.resolve("projects");
        this.batchBaseDir = storage.resolve("batch-split");
        this.ruleValidationService = ruleValidationService;
    }

    /** Kept for callers that explicitly need the complete legacy response. */
    public ProjectListResponse listProjects() {
        return listProjects(null, null, null, null, null, null, null, 1, Integer.MAX_VALUE);
    }

    public ProjectListResponse listProjects(String search,
                                            String projectName,
                                            String splitLevel,
                                            String sourceType,
                                            String status,
                                            String risk,
                                            String exportStatus,
                                            int page,
                                            int pageSize) {
        List<ProjectListItem> allItems = loadProjectItems();
        Map<String, BatchAssociation> associations = loadBatchAssociations();
        for (ProjectListItem item : allItems) {
            BatchAssociation association = associations.get(item.getProjectId());
            if (association != null && !StringUtils.hasText(item.getBatchId())) {
                item.setBatchId(association.batchId);
                item.setSplitLevel(association.splitLevel);
                if (item.getIssueCount() == 0 && association.issueCount != null) {
                    item.setIssueCount(association.issueCount);
                    item.setErrorCount(value(association.errorCount));
                    item.setRiskCount(value(association.riskCount));
                }
            }
        }

        List<ProjectListItem> filtered = new ArrayList<ProjectListItem>();
        for (ProjectListItem item : allItems) {
            if (!matchesBasicFilters(item, search, projectName, splitLevel, sourceType, status, exportStatus)) {
                continue;
            }
            if (StringUtils.hasText(risk)) {
                enrichValidationSummary(item);
                if (!matchesRisk(item, risk)) {
                    continue;
                }
            }
            filtered.add(item);
        }
        Collections.sort(filtered, updatedAtComparator());

        int normalizedPageSize = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        if (pageSize == Integer.MAX_VALUE) {
            normalizedPageSize = Math.max(1, filtered.size());
        }
        int totalPages = Math.max(1, (int) Math.ceil(filtered.size() / (double) normalizedPageSize));
        int normalizedPage = Math.min(totalPages, Math.max(1, page));
        int from = Math.min(filtered.size(), (normalizedPage - 1) * normalizedPageSize);
        int to = Math.min(filtered.size(), from + normalizedPageSize);
        List<ProjectListItem> pageItems = new ArrayList<ProjectListItem>(filtered.subList(from, to));
        for (ProjectListItem item : pageItems) {
            enrichValidationSummary(item);
        }

        ProjectListResponse response = new ProjectListResponse();
        response.setProjects(pageItems);
        response.setOverallTotal(allItems.size());
        response.setTotal(filtered.size());
        response.setPage(normalizedPage);
        response.setPageSize(normalizedPageSize);
        response.setTotalPages(totalPages);
        response.setStatuses(statuses(allItems));
        response.setTotalWithRisks(countKnownRisks(filtered));
        response.setTotalExported(countExported(filtered));
        return response;
    }

    public ProjectListItem getProject(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            throw new BusinessException("Project id is required");
        }
        List<ProjectListItem> items = loadProjectItems();
        Map<String, BatchAssociation> associations = loadBatchAssociations();
        for (ProjectListItem item : items) {
            if (projectId.equals(item.getProjectId())) {
                BatchAssociation association = associations.get(projectId);
                if (association != null && !StringUtils.hasText(item.getBatchId())) {
                    item.setBatchId(association.batchId);
                    item.setSplitLevel(association.splitLevel);
                }
                enrichValidationSummary(item);
                return item;
            }
        }
        throw new BusinessException("Project not found: " + projectId);
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
                public int compare(Path left, Path right) { return right.compareTo(left); }
            });
            for (Path path : deletePaths) {
                Files.deleteIfExists(path);
            }
            metadataCache.remove(projectId);
            summaryCache.remove(projectId);
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
            if (StringUtils.hasText(projectId)) uniqueIds.add(projectId.trim());
        }
        if (uniqueIds.isEmpty()) throw new BusinessException("请先选择要删除的项目");
        for (String projectId : uniqueIds) deleteProject(projectId);
        return uniqueIds.size();
    }

    private List<ProjectListItem> loadProjectItems() {
        List<ProjectListItem> items = new ArrayList<ProjectListItem>();
        if (!Files.exists(projectsDir)) return items;
        Set<String> existingIds = new HashSet<String>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectsDir)) {
            for (Path projectDir : stream) {
                if (!Files.isDirectory(projectDir)) continue;
                Path metadataPath = projectDir.resolve("metadata.json");
                if (!Files.isRegularFile(metadataPath)) continue;
                ProjectMetadata metadata = readMetadata(metadataPath);
                if (metadata == null || !StringUtils.hasText(metadata.getId())) continue;
                existingIds.add(metadata.getId());
                items.add(toListItem(metadata));
            }
        } catch (IOException ignored) {
            // Return readable entries rather than failing the complete management page.
        }
        metadataCache.keySet().retainAll(existingIds);
        summaryCache.keySet().retainAll(existingIds);
        return items;
    }

    private ProjectMetadata readMetadata(Path metadataPath) {
        String key = metadataPath.getParent().getFileName().toString();
        try {
            long modified = Files.getLastModifiedTime(metadataPath).toMillis();
            long size = Files.size(metadataPath);
            CachedMetadata cached = metadataCache.get(key);
            if (cached != null && cached.modified == modified && cached.size == size) return cached.metadata;
            ProjectMetadata metadata = objectMapper.readValue(metadataPath.toFile(), ProjectMetadata.class);
            metadataCache.put(key, new CachedMetadata(modified, size, metadata));
            return metadata;
        } catch (IOException ex) {
            return null;
        }
    }

    private ProjectListItem toListItem(ProjectMetadata metadata) {
        ProjectListItem item = new ProjectListItem();
        item.setProjectId(metadata.getId());
        item.setProjectName(metadata.getName());
        item.setSplitLevel(normalizeSplitLevel(metadata.getSplitLevel()));
        item.setBatchId(metadata.getBatchId());
        item.setSourceFileName(metadata.getSourceFileName());
        item.setSourceType(sourceType(metadata));
        item.setStatus(metadata.getStatus());
        item.setCreatedAt(metadata.getCreatedAt());
        item.setUpdatedAt(metadata.getUpdatedAt());
        item.setExcelExported(fileExists(metadata.getExcelOutputPath()));
        item.setBusinessExported(fileExists(metadata.getBusinessRequirementOutputPath()));
        item.setWordExported(fileExists(metadata.getWordOutputPath()));
        item.setSplitResultReady(fileExists(metadata.getFunctionSplitRowsPath()));
        item.setExportStatus(exportStatus(item));
        if (metadata.getFunctionCount() != null) item.setFunctionCount(metadata.getFunctionCount());
        if (metadata.getIssueCount() != null) item.setIssueCount(metadata.getIssueCount());
        if (metadata.getErrorCount() != null) item.setErrorCount(metadata.getErrorCount());
        if (metadata.getRiskCount() != null) item.setRiskCount(metadata.getRiskCount());
        if (metadata.getSuggestionCount() != null) item.setSuggestionCount(metadata.getSuggestionCount());
        return item;
    }

    private void enrichValidationSummary(ProjectListItem item) {
        CachedMetadata cachedMetadata = metadataCache.get(item.getProjectId());
        if (cachedMetadata == null) return;
        ProjectMetadata metadata = cachedMetadata.metadata;
        if (metadata.getFunctionCount() != null && metadata.getIssueCount() != null) return;
        String path = metadata.getFunctionSplitRowsPath();
        if (!StringUtils.hasText(path) || !Files.isRegularFile(Paths.get(path))) return;
        try {
            Path rowsPath = Paths.get(path);
            long modified = Files.getLastModifiedTime(rowsPath).toMillis();
            long size = Files.size(rowsPath);
            CachedSummary summary = summaryCache.get(item.getProjectId());
            if (summary == null || summary.modified != modified || summary.size != size) {
                List<FunctionSplitRow> rows = objectMapper.readValue(rowsPath.toFile(), new TypeReference<List<FunctionSplitRow>>() { });
                Set<String> processes = new LinkedHashSet<String>();
                for (FunctionSplitRow row : rows) {
                    if (StringUtils.hasText(row.getFunctionProcess())) processes.add(row.getFunctionProcess().trim());
                }
                RuleValidationResponse validation = ruleValidationService.validate(rows);
                summary = new CachedSummary(modified, size, processes.size(), validation);
                summaryCache.put(item.getProjectId(), summary);
            }
            summary.apply(item);
        } catch (IOException ignored) {
            // Keep zero-valued summary when an old/corrupt result cannot be read.
        }
    }

    private Map<String, BatchAssociation> loadBatchAssociations() {
        Map<String, BatchAssociation> associations = new HashMap<String, BatchAssociation>();
        if (!Files.exists(batchBaseDir)) return associations;
        Set<String> existing = new HashSet<String>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(batchBaseDir, "BS*")) {
            for (Path dir : dirs) {
                Path statePath = dir.resolve("state.json");
                if (!Files.isRegularFile(statePath)) continue;
                String batchId = dir.getFileName().toString();
                existing.add(batchId);
                CachedBatch cached = readBatch(statePath, batchId);
                if (cached != null) associations.putAll(cached.associations);
            }
        } catch (IOException ignored) {
            // Metadata-level association continues to work even if old batch state is unreadable.
        }
        batchCache.keySet().retainAll(existing);
        return associations;
    }

    private CachedBatch readBatch(Path statePath, String batchId) {
        try {
            long modified = Files.getLastModifiedTime(statePath).toMillis();
            long size = Files.size(statePath);
            CachedBatch cached = batchCache.get(batchId);
            if (cached != null && cached.modified == modified && cached.size == size) return cached;
            JsonNode root = objectMapper.readTree(statePath.toFile());
            // Project-group members remain requirement-level projects. The PJ entity,
            // rather than every member row, represents the project-level aggregate.
            if (StringUtils.hasText(text(root.get("projectGroupId")))) {
                cached = new CachedBatch(modified, size, new HashMap<String, BatchAssociation>());
                batchCache.put(batchId, cached);
                return cached;
            }
            String level = normalizeSplitLevel(text(root.get("splitLevel")));
            Map<String, BatchAssociation> values = new HashMap<String, BatchAssociation>();
            JsonNode items = root.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    String projectId = text(item.get("projectId"));
                    if (!StringUtils.hasText(projectId)) continue;
                    values.put(projectId, new BatchAssociation(batchId, level,
                            integer(item.get("issueCount")), integer(item.get("errorCount")), integer(item.get("riskCount"))));
                }
            }
            cached = new CachedBatch(modified, size, values);
            batchCache.put(batchId, cached);
            return cached;
        } catch (IOException ex) {
            return null;
        }
    }

    private boolean matchesBasicFilters(ProjectListItem item, String search, String projectName, String splitLevel,
                                        String sourceType, String status, String exportStatus) {
        if (StringUtils.hasText(projectName) && !projectName.trim().equals(item.getProjectName())) return false;
        if (StringUtils.hasText(splitLevel) && !normalizeSplitLevel(splitLevel).equals(item.getSplitLevel())) return false;
        if (StringUtils.hasText(sourceType) && !sourceType.trim().equalsIgnoreCase(safe(item.getSourceType()))) return false;
        if (StringUtils.hasText(status) && !status.trim().equals(item.getStatus())) return false;
        if (StringUtils.hasText(exportStatus) && !exportStatus.trim().equals(item.getExportStatus())) return false;
        if (!StringUtils.hasText(search)) return true;
        String needle = search.trim().toLowerCase(Locale.ROOT);
        return contains(item.getProjectId(), needle) || contains(item.getProjectName(), needle)
                || contains(item.getSourceFileName(), needle) || contains(item.getStatus(), needle)
                || contains(item.getBatchId(), needle);
    }

    private boolean matchesRisk(ProjectListItem item, String risk) {
        if ("ERROR".equalsIgnoreCase(risk)) return item.getErrorCount() > 0;
        if ("RISK".equalsIgnoreCase(risk)) return item.getRiskCount() > 0;
        if ("OK".equalsIgnoreCase(risk)) return item.getIssueCount() <= 0;
        return true;
    }

    private List<String> statuses(List<ProjectListItem> items) {
        Set<String> values = new LinkedHashSet<String>();
        for (ProjectListItem item : items) if (StringUtils.hasText(item.getStatus())) values.add(item.getStatus());
        List<String> result = new ArrayList<String>(values);
        Collections.sort(result);
        return result;
    }

    private int countKnownRisks(List<ProjectListItem> items) {
        int count = 0;
        for (ProjectListItem item : items) {
            CachedSummary summary = summaryCache.get(item.getProjectId());
            if (item.getIssueCount() > 0 || (summary != null && summary.issueCount > 0)) count++;
        }
        return count;
    }

    private int countExported(List<ProjectListItem> items) {
        int count = 0;
        for (ProjectListItem item : items) if ("全部已导出".equals(item.getExportStatus())) count++;
        return count;
    }

    private Comparator<ProjectListItem> updatedAtComparator() {
        return new Comparator<ProjectListItem>() {
            @Override
            public int compare(ProjectListItem left, ProjectListItem right) {
                return safe(right.getUpdatedAt()).compareTo(safe(left.getUpdatedAt()));
            }
        };
    }

    private String sourceType(ProjectMetadata metadata) {
        String status = safe(metadata.getStatus());
        String storedPath = safe(metadata.getStoredFilePath());
        return status.startsWith("EXCEL") || storedPath.contains("split-review") ? "EXCEL" : "DOCUMENT";
    }

    private boolean fileExists(String path) { return StringUtils.hasText(path) && Files.exists(Paths.get(path)); }

    private String exportStatus(ProjectListItem item) {
        if (item.isExcelExported() && item.isBusinessExported() && item.isWordExported()) return "全部已导出";
        if (item.isExcelExported() || item.isBusinessExported() || item.isWordExported()) return "部分已导出";
        return "未导出";
    }

    private String normalizeSplitLevel(String value) {
        return "PROJECT".equalsIgnoreCase(safe(value).trim()) ? "PROJECT" : "REQUIREMENT";
    }

    private boolean contains(String value, String needle) { return safe(value).toLowerCase(Locale.ROOT).contains(needle); }
    private String safe(String value) { return value == null ? "" : value; }
    private String text(JsonNode node) { return node == null || node.isNull() ? "" : node.asText(""); }
    private Integer integer(JsonNode node) { return node == null || !node.isNumber() ? null : node.asInt(); }
    private int value(Integer value) { return value == null ? 0 : value; }

    private static final class CachedMetadata {
        private final long modified;
        private final long size;
        private final ProjectMetadata metadata;
        private CachedMetadata(long modified, long size, ProjectMetadata metadata) {
            this.modified = modified; this.size = size; this.metadata = metadata;
        }
    }

    private static final class CachedSummary {
        private final long modified;
        private final long size;
        private final int functionCount;
        private final int issueCount;
        private final int errorCount;
        private final int riskCount;
        private final int suggestionCount;
        private CachedSummary(long modified, long size, int functionCount, RuleValidationResponse validation) {
            this.modified = modified; this.size = size; this.functionCount = functionCount;
            this.issueCount = validation.getIssueCount(); this.errorCount = validation.getErrorCount();
            this.riskCount = validation.getRiskCount(); this.suggestionCount = validation.getSuggestionCount();
        }
        private void apply(ProjectListItem item) {
            item.setFunctionCount(functionCount); item.setIssueCount(issueCount); item.setErrorCount(errorCount);
            item.setRiskCount(riskCount); item.setSuggestionCount(suggestionCount);
        }
    }

    private static final class CachedBatch {
        private final long modified;
        private final long size;
        private final Map<String, BatchAssociation> associations;
        private CachedBatch(long modified, long size, Map<String, BatchAssociation> associations) {
            this.modified = modified; this.size = size; this.associations = associations;
        }
    }

    private static final class BatchAssociation {
        private final String batchId;
        private final String splitLevel;
        private final Integer issueCount;
        private final Integer errorCount;
        private final Integer riskCount;
        private BatchAssociation(String batchId, String splitLevel, Integer issueCount, Integer errorCount, Integer riskCount) {
            this.batchId = batchId; this.splitLevel = splitLevel; this.issueCount = issueCount;
            this.errorCount = errorCount; this.riskCount = riskCount;
        }
    }
}
