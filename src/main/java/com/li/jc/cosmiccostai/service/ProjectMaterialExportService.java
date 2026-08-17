package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Produces individual requirement materials and one five-attachment project bundle
 * from the final reviewed rows of a completed batch.
 */
@Service
public class ProjectMaterialExportService {

    private static final Pattern LEADING_DOCUMENT_SEQUENCE = Pattern.compile(
            "^\\s*(?:(?:第\\s*(?:\\d{1,3}|[一二三四五六七八九十百]{1,4})\\s*(?:章|节|部分|篇)\\s*)"
                    + "|(?:[（(\\[]\\s*(?:\\d{1,3}|[一二三四五六七八九十百]{1,4})\\s*[)）\\]]\\s*)"
                    + "|(?:(?:\\d{1,3}(?:\\s*[.．]\\s*\\d{1,3})*|[一二三四五六七八九十百]{1,4})"
                    + "\\s*(?:[.．、:：_\\-—]+\\s*|\\s+)))+");

    public static final String ATTACHMENT_1 = "attachment1";
    public static final String ATTACHMENT_2 = "attachment2";
    public static final String ATTACHMENT_3 = "attachment3";
    public static final String ATTACHMENT_4 = "attachment4";
    public static final String ATTACHMENT_5 = "attachment5";
    public static final String ARCHIVE = "archive";

    private final FileStorageService fileStorageService;
    private final ExcelExportService excelExportService;
    private final WordExportService wordExportService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path outputBaseDir;

    public ProjectMaterialExportService(FileStorageService fileStorageService,
                                        ExcelExportService excelExportService,
                                        WordExportService wordExportService,
                                        @Value("${cosmic.output.base-dir:output}") String outputBaseDir) {
        this.fileStorageService = fileStorageService;
        this.excelExportService = excelExportService;
        this.wordExportService = wordExportService;
        this.outputBaseDir = Paths.get(outputBaseDir);
    }

    public ProjectMaterialBundle export(String batchId, List<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            throw new BusinessException("项目级送审材料至少需要一个已完成拆分的需求。");
        }

        List<FunctionSplitRow> aggregateRows = new ArrayList<FunctionSplitRow>();
        List<ProjectMetadata> projects = new ArrayList<ProjectMetadata>();
        String projectName = null;
        for (String projectId : projectIds) {
            ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
            if (metadata == null) {
                throw new BusinessException("项目级送审材料找不到需求项目：" + projectId);
            }
            projects.add(metadata);
            if (!StringUtils.hasText(projectName) && StringUtils.hasText(metadata.getName())) {
                projectName = metadata.getName().trim();
            }
            aggregateRows.addAll(readAndMapRows(metadata, projectName));
        }
        if (!StringUtils.hasText(projectName)) {
            projectName = "批量需求项目";
        }
        // Project name is chosen before mapping in normal uploads. Normalize it once more
        // for legacy rows whose first metadata did not contain a filed project name.
        for (FunctionSplitRow row : aggregateRows) {
            row.setProjectName(projectName);
        }

        // Keep the existing per-requirement deliverables available in project mode.
        for (ProjectMetadata project : projects) {
            excelExportService.export(project.getId());
            wordExportService.export(project.getId());
        }

        Path outputDir = outputBaseDir.resolve("project-materials").resolve(batchId);
        ExcelExportService.ProjectExcelExportResult excel = excelExportService.exportProjectMaterial(
                batchId, projectName, aggregateRows, outputDir);
        WordExportService.ProjectWordExportResult word = wordExportService.exportProjectMaterial(
                batchId, projectName, aggregateRows, outputDir);
        Path attachment4 = exportAttachment4(projectName, excel.getTotalCfp(), outputDir);
        Path attachment5 = exportAttachment5(projectName, excel.getTotalCfp(), outputDir);

        LinkedHashMap<String, Path> files = new LinkedHashMap<String, Path>();
        files.put(ATTACHMENT_1, word.getAttachment1Path());
        files.put(ATTACHMENT_2, excel.getAttachment2Path());
        files.put(ATTACHMENT_3, excel.getAttachment3Path());
        files.put(ATTACHMENT_4, attachment4);
        files.put(ATTACHMENT_5, attachment5);
        Path archive = outputDir.resolve(sanitizeFileName(projectName) + "-COSMIC送审材料.zip");
        writeArchive(archive, files.values());
        files.put(ARCHIVE, archive);

        ProjectMaterialBundle bundle = new ProjectMaterialBundle();
        bundle.projectName = projectName;
        bundle.requirementCount = excel.getRequirementCount();
        bundle.totalCfp = excel.getTotalCfp();
        bundle.files.putAll(files);
        return bundle;
    }

    /** Generates each completed requirement's normal attachment 1-3 archive and wraps those ZIP files in one batch ZIP. */
    public RequirementBatchArchive exportRequirementArchives(String batchId,
                                                             List<String> projectIds) {
        return exportRequirementArchives(batchId, projectIds, null);
    }

    public RequirementBatchArchive exportRequirementArchives(String batchId,
                                                             List<String> projectIds,
                                                             RequirementArchiveProgressListener listener) {
        return exportRequirementArchives(batchId, projectIds, listener, true);
    }

    /** Wraps the per-requirement exports just refreshed by project material generation without writing them twice. */
    public RequirementBatchArchive exportExistingRequirementArchives(String batchId,
                                                                     List<String> projectIds,
                                                                     RequirementArchiveProgressListener listener) {
        return exportRequirementArchives(batchId, projectIds, listener, false);
    }

    private RequirementBatchArchive exportRequirementArchives(String batchId,
                                                              List<String> projectIds,
                                                              RequirementArchiveProgressListener listener,
                                                              boolean refreshRequirementExports) {
        if (projectIds == null || projectIds.isEmpty()) {
            throw new BusinessException("需求级材料整合包至少需要一个已完成需求。");
        }
        Path outputDir = outputBaseDir.resolve("batch-requirement-materials").resolve(batchId);
        try {
            Files.createDirectories(outputDir);
        } catch (IOException ex) {
            throw new BusinessException("创建需求级材料整合目录失败。", ex);
        }

        List<Path> requirementArchives = new ArrayList<Path>();
        List<String> failedProjects = new ArrayList<String>();
        Map<String, Integer> names = new LinkedHashMap<String, Integer>();
        for (String projectId : projectIds) {
            try {
                ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
                if (metadata == null) {
                    throw new BusinessException("需求项目不存在");
                }
                if (refreshRequirementExports) {
                    excelExportService.export(projectId);
                    wordExportService.export(projectId);
                }
                metadata = fileStorageService.loadMetadata(projectId);
                Path excel = requiredExport(metadata.getExcelOutputPath(), "附件3功能点拆分表");
                Path business = requiredExport(metadata.getBusinessRequirementOutputPath(), "附件2需求汇总表");
                Path word = requiredExport(metadata.getWordOutputPath(), "附件1需求说明书");
                String baseName = sanitizeFileName(requirementArchiveBaseName(metadata));
                int duplicate = names.containsKey(baseName) ? names.get(baseName) + 1 : 1;
                names.put(baseName, duplicate);
                String archiveName = baseName + (duplicate > 1 ? "-" + duplicate : "") + "-送审材料.zip";
                Path requirementArchive = outputDir.resolve(archiveName);
                writeArchive(requirementArchive, Arrays.asList(word, business, excel));
                requirementArchives.add(requirementArchive);
            } catch (RuntimeException ex) {
                failedProjects.add(projectId + "：" + rootMessage(ex));
            }
            if (listener != null) {
                listener.onProgress(requirementArchives.size(), failedProjects.size(), projectIds.size());
            }
        }
        if (requirementArchives.isEmpty()) {
            throw new BusinessException("没有成功生成任何单需求送审材料：" + String.join("；", failedProjects));
        }
        Path batchArchive = outputDir.resolve(batchId + "-全部单需求送审材料.zip");
        writeArchive(batchArchive, requirementArchives);

        RequirementBatchArchive result = new RequirementBatchArchive();
        result.archivePath = batchArchive;
        result.successCount = requirementArchives.size();
        result.failedProjects.addAll(failedProjects);
        return result;
    }

    private Path requiredExport(String path, String label) {
        if (!StringUtils.hasText(path)) {
            throw new BusinessException(label + "未生成");
        }
        Path file = Paths.get(path).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(label + "文件不存在：" + file.getFileName());
        }
        return file;
    }

    private String requirementArchiveBaseName(ProjectMetadata metadata) {
        String source = metadata == null ? "" : metadata.getSourceFileName();
        if (!StringUtils.hasText(source) && metadata != null) {
            source = metadata.getName();
        }
        String name = StringUtils.hasText(source) ? source.trim() : metadata.getId();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return StringUtils.hasText(current.getMessage()) ? current.getMessage() : error.getClass().getSimpleName();
    }

    /** Deletes every aggregate material version owned by one project-level group. */
    public int deleteMaterialVersions(String projectGroupId, String batchId) {
        String group = safeMaterialId(projectGroupId, "PJ");
        String batch = safeMaterialId(batchId, "BS");
        Path root = outputBaseDir.resolve("project-materials").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int deleted = 0;
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
            for (Path directory : directories) {
                if (!Files.isDirectory(directory)) continue;
                String name = directory.getFileName().toString();
                if (name.equals(group) || name.startsWith(group + "-V")
                        || name.equals(batch) || name.startsWith(batch + "-V")) {
                    deleteDirectory(directory, root);
                    deleted++;
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("删除项目级材料版本失败。", ex);
        }
        return deleted;
    }

    /** Deletes the aggregate ZIP that contains every single-requirement review archive for one batch. */
    public int deleteRequirementArchive(String batchId) {
        String batch = safeMaterialId(batchId, "BS");
        Path root = outputBaseDir.resolve("batch-requirement-materials").toAbsolutePath().normalize();
        Path directory = root.resolve(batch).normalize();
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try {
            deleteDirectory(directory, root);
            return 1;
        } catch (IOException ex) {
            throw new BusinessException("删除单需求送审材料总包失败。", ex);
        }
    }

    /** Deletes the individual exported material folders for requirements owned by a deleted group. */
    public int deleteRequirementOutputs(List<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return 0;
        }
        Path root = outputBaseDir.toAbsolutePath().normalize();
        int deleted = 0;
        Set<String> unique = new LinkedHashSet<String>();
        for (String projectId : projectIds) {
            unique.add(safeMaterialId(projectId, "P"));
        }
        try {
            for (String projectId : unique) {
                Path directory = root.resolve(projectId).normalize();
                if (!Files.isDirectory(directory)) continue;
                deleteDirectory(directory, root);
                deleted++;
            }
        } catch (IOException ex) {
            throw new BusinessException("删除关联需求的导出材料失败。", ex);
        }
        return deleted;
    }

    private String safeMaterialId(String value, String prefix) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches(prefix + "[0-9A-Z]+")) {
            throw new BusinessException("无效的项目级材料编号。");
        }
        return normalized;
    }

    private void deleteDirectory(Path directory, Path allowedRoot) throws IOException {
        Path root = allowedRoot.toAbsolutePath().normalize();
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new BusinessException("项目级材料删除路径非法，已拒绝操作。");
        }
        try (Stream<Path> paths = Files.walk(target)) {
            List<Path> ordered = new ArrayList<Path>();
            paths.forEach(ordered::add);
            Collections.sort(ordered, new Comparator<Path>() {
                @Override
                public int compare(Path left, Path right) {
                    return right.compareTo(left);
                }
            });
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        }
    }

    private List<FunctionSplitRow> readAndMapRows(ProjectMetadata metadata, String projectName) {
        if (!StringUtils.hasText(metadata.getFunctionSplitRowsPath())) {
            throw new BusinessException("需求尚无最终功能点拆分表：" + sourceName(metadata));
        }
        Path path = Paths.get(metadata.getFunctionSplitRowsPath());
        if (!Files.exists(path)) {
            throw new BusinessException("需求功能点拆分表不存在：" + sourceName(metadata));
        }
        List<FunctionSplitRow> rows;
        try {
            rows = objectMapper.readValue(path.toFile(), new TypeReference<List<FunctionSplitRow>>() { });
        } catch (IOException ex) {
            throw new BusinessException("读取需求功能点拆分表失败：" + sourceName(metadata), ex);
        }
        String demandName = normalizeRequirementChapterName(sourceBaseName(metadata));
        List<FunctionSplitRow> mapped = new ArrayList<FunctionSplitRow>();
        for (FunctionSplitRow source : rows) {
            FunctionSplitRow row = copyRow(source);
            String originalLevel1 = firstText(source.getLevel1Module(), source.getLevel2Module(), demandName);
            String originalLevel2 = firstText(source.getLevel2Module(), source.getLevel3Module(), originalLevel1);
            String originalLevel3 = firstText(source.getLevel3Module(), source.getFunctionProcess(), originalLevel2);
            row.setProjectName(projectName);
            row.setLevel1Module(demandName);
            row.setLevel2Module(originalLevel1);
            row.setLevel3Module(firstDifferent(originalLevel3, originalLevel1, originalLevel2));
            mapped.add(row);
        }
        return mapped;
    }

    private FunctionSplitRow copyRow(FunctionSplitRow source) {
        FunctionSplitRow target = new FunctionSplitRow();
        target.setProjectName(source.getProjectName());
        target.setLevel1Module(source.getLevel1Module());
        target.setLevel2Module(source.getLevel2Module());
        target.setLevel3Module(source.getLevel3Module());
        target.setFunctionalUser(source.getFunctionalUser());
        target.setTriggerEvent(source.getTriggerEvent());
        target.setFunctionProcess(source.getFunctionProcess());
        target.setFunctionDescription(source.getFunctionDescription());
        target.setSubProcessDescription(source.getSubProcessDescription());
        target.setMovementType(source.getMovementType());
        target.setDataGroup(source.getDataGroup());
        target.setDataAttributes(source.getDataAttributes());
        target.setSequenceDiagram(source.getSequenceDiagram());
        target.setReuseDegree(source.getReuseDegree());
        target.setCfp(source.getCfp());
        target.setEvidence(source.getEvidence());
        target.setSourceReferences(source.getSourceReferences() == null
                ? new ArrayList<String>() : new ArrayList<String>(source.getSourceReferences()));
        return target;
    }

    private Path exportAttachment4(String projectName, double totalCfp, Path outputDir) {
        Path template = resolveTemplate("附件4");
        Path output = outputDir.resolve("附件4-" + sanitizeFileName(projectName) + "台账V1.0.xlsx");
        try {
            Files.createDirectories(outputDir);
            try (InputStream inputStream = Files.newInputStream(template);
                 Workbook workbook = new XSSFWorkbook(inputStream)) {
                Sheet sheet = workbook.getSheetAt(0);
                for (int rowIndex = 2; rowIndex <= 7; rowIndex++) {
                    clearRowValues(sheet, rowIndex, 15);
                }
                Row row = row(sheet, 2);
                setNumber(row, 0, 1D);
                setText(row, 1, projectName);
                setText(row, 2, "项目预算估算");
                for (int column = 3; column <= 9; column++) setText(row, column, "");
                setNumber(row, 10, totalCfp);
                setNumber(row, 11, totalCfp);
                setText(row, 12, "");
                setText(row, 13, "");
                setText(row, 14, "本表由批量项目级拆分自动汇总；工作量暂按送审功能点 1:1 估算，负责人、预算及单价请送审前补充。");
                try (OutputStream outputStream = Files.newOutputStream(output)) {
                    workbook.write(outputStream);
                }
            }
            return output;
        } catch (IOException ex) {
            throw new BusinessException("生成附件4项目台账失败：" + ex.getMessage(), ex);
        }
    }

    private Path exportAttachment5(String projectName, double totalCfp, Path outputDir) {
        Path template = resolveTemplate("附件5");
        Path output = outputDir.resolve("附件5-" + sanitizeFileName(projectName)
                + "定制化软件工作量评估申请表V1.0.xlsx");
        try {
            Files.createDirectories(outputDir);
            try (InputStream inputStream = Files.newInputStream(template);
                 Workbook workbook = new XSSFWorkbook(inputStream)) {
                Sheet sheet = workbook.getSheetAt(0);
                clearInputCells(sheet, 1, 1, 3, 5);
                clearInputCells(sheet, 2, 1, 5);
                clearInputCells(sheet, 3, 1, 3, 5);
                clearInputCells(sheet, 4, 1, 3, 5);
                clearInputCells(sheet, 5, 1);
                clearInputCells(sheet, 6, 1);
                for (int rowIndex = 7; rowIndex <= 11; rowIndex++) {
                    clearInputCells(sheet, rowIndex, 1, 2, 4, 5);
                }
                setText(row(sheet, 2), 1, projectName);
                setText(row(sheet, 4), 1, formatNumber(totalCfp) + "人天");
                setText(row(sheet, 6), 1,
                        "本申请表由批量项目级拆分自动汇总，送审工作量共计 " + formatNumber(totalCfp)
                                + " 人天；预算、单价、送审金额、供应商及签字信息请送审前补充。");
                try (OutputStream outputStream = Files.newOutputStream(output)) {
                    workbook.write(outputStream);
                }
            }
            return output;
        } catch (IOException ex) {
            throw new BusinessException("生成附件5工作量评估申请表失败：" + ex.getMessage(), ex);
        }
    }

    private void writeArchive(Path archive, Iterable<Path> files) {
        try (OutputStream outputStream = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(outputStream)) {
            byte[] buffer = new byte[8192];
            for (Path file : files) {
                zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
                try (InputStream inputStream = Files.newInputStream(file)) {
                    int read;
                    while ((read = inputStream.read(buffer)) >= 0) {
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new BusinessException("生成项目级送审材料压缩包失败：" + ex.getMessage(), ex);
        }
    }

    private Path resolveTemplate(String prefix) {
        Path template = findTemplate(Paths.get("templates"), prefix);
        if (template == null) template = findTemplate(Paths.get(".."), prefix);
        if (template == null) throw new BusinessException(prefix + " 模板不存在。");
        return template;
    }

    private Path findTemplate(Path directory, String prefix) {
        if (!Files.isDirectory(directory)) return null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.xlsx")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.startsWith(prefix) && !name.startsWith("~$")) return path;
            }
        } catch (IOException ex) {
            throw new BusinessException("查找 " + prefix + " 模板失败。", ex);
        }
        return null;
    }

    private void clearRowValues(Sheet sheet, int rowIndex, int columns) {
        Row row = row(sheet, rowIndex);
        for (int column = 0; column < columns; column++) setText(row, column, "");
    }

    private void clearInputCells(Sheet sheet, int rowIndex, int... columns) {
        Row row = row(sheet, rowIndex);
        for (int column : columns) setText(row, column, "");
    }

    private Row row(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        return row == null ? sheet.createRow(rowIndex) : row;
    }

    private Cell cell(Row row, int column) {
        Cell cell = row.getCell(column);
        return cell == null ? row.createCell(column) : cell;
    }

    private void setText(Row row, int column, String value) {
        Cell cell = cell(row, column);
        if (StringUtils.hasText(value)) {
            cell.setCellValue(value);
        } else {
            cell.setBlank();
        }
    }

    private void setNumber(Row row, int column, double value) {
        cell(row, column).setCellValue(value);
    }

    private String sourceName(ProjectMetadata metadata) {
        return StringUtils.hasText(metadata.getSourceFileName()) ? metadata.getSourceFileName() : metadata.getId();
    }

    private String sourceBaseName(ProjectMetadata metadata) {
        String name = sourceName(metadata);
        name = Paths.get(name).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    String normalizeRequirementChapterName(String sourceBaseName) {
        String original = sourceBaseName == null ? "" : sourceBaseName.trim();
        String normalized = LEADING_DOCUMENT_SEQUENCE.matcher(original).replaceFirst("").trim();
        return StringUtils.hasText(normalized) ? normalized : original;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "未分类模块";
    }

    private String firstDifferent(String preferred, String compared, String fallback) {
        if (StringUtils.hasText(preferred) && !preferred.trim().equals(compared)) return preferred.trim();
        return firstText(fallback, preferred, compared);
    }

    private String sanitizeFileName(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return StringUtils.hasText(sanitized) ? sanitized : "未命名项目";
    }

    private String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001D) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    public static class ProjectMaterialBundle {
        private String projectName;
        private int requirementCount;
        private double totalCfp;
        private final Map<String, Path> files = new LinkedHashMap<String, Path>();

        public String getProjectName() { return projectName; }
        public int getRequirementCount() { return requirementCount; }
        public double getTotalCfp() { return totalCfp; }
        public Map<String, Path> getFiles() { return Collections.unmodifiableMap(files); }
        public Path getFile(String type) { return files.get(type); }
    }

    public static class RequirementBatchArchive {
        private Path archivePath;
        private int successCount;
        private final List<String> failedProjects = new ArrayList<String>();

        public Path getArchivePath() { return archivePath; }
        public int getSuccessCount() { return successCount; }
        public List<String> getFailedProjects() { return Collections.unmodifiableList(failedProjects); }
    }

    public interface RequirementArchiveProgressListener {
        void onProgress(int successCount, int failedCount, int totalCount);
    }
}
