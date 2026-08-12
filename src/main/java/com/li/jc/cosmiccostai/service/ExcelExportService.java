package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.ExcelExportResponse;
import com.li.jc.cosmiccostai.dto.RuleValidationIssue;
import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.model.CosmicResult;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.RegionUtil;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ExcelExportService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int TEMPLATE_CLEAR_ROW_LIMIT = 500;
    private static final int FIRST_NON_MERGED_EXPORT_COLUMN = 7;
    private static final int CFP_COLUMN = 12;
    private static final int RISK_REASON_COLUMN = 13;
    private static final int STANDARD_LAST_EXPORT_COLUMN = RISK_REASON_COLUMN;
    private static final int FIRST_SPLIT_DATA_ROW = 5;
    private static final String VALIDATION_ERROR = "ERROR";
    private static final String VALIDATION_RISK = "RISK";
    private static final Pattern LOGICAL_ROW_REFERENCE = Pattern.compile("第\\s*(\\d+)\\s*行");
    private static final byte[] VALIDATION_RISK_FILL_RGB = new byte[]{(byte) 255, (byte) 242, (byte) 204};
    private static final byte[] VALIDATION_ERROR_FILL_RGB = new byte[]{(byte) 244, (byte) 204, (byte) 204};

    private final FileStorageService fileStorageService;
    private final FunctionDescriptionAiService functionDescriptionAiService;
    private final RuleValidationService ruleValidationService;
    private final ObjectMapper objectMapper;
    private final Path outputBaseDir;
    private final String configuredTemplatePath;

    public ExcelExportService(FileStorageService fileStorageService,
                              @Value("${cosmic.output.base-dir:output}") String outputBaseDir,
                              @Value("${cosmic.template.excel-path:}") String configuredTemplatePath) {
        this(fileStorageService, outputBaseDir, configuredTemplatePath, new FunctionDescriptionAiService());
    }

    public ExcelExportService(FileStorageService fileStorageService,
                              @Value("${cosmic.output.base-dir:output}") String outputBaseDir,
                              @Value("${cosmic.template.excel-path:}") String configuredTemplatePath,
                              FunctionDescriptionAiService functionDescriptionAiService) {
        this(fileStorageService, outputBaseDir, configuredTemplatePath,
                functionDescriptionAiService, new RuleValidationService());
    }

    @Autowired
    public ExcelExportService(FileStorageService fileStorageService,
                              @Value("${cosmic.output.base-dir:output}") String outputBaseDir,
                              @Value("${cosmic.template.excel-path:}") String configuredTemplatePath,
                              FunctionDescriptionAiService functionDescriptionAiService,
                              RuleValidationService ruleValidationService) {
        this.fileStorageService = fileStorageService;
        this.functionDescriptionAiService = functionDescriptionAiService;
        this.ruleValidationService = ruleValidationService;
        this.outputBaseDir = Paths.get(outputBaseDir);
        this.configuredTemplatePath = configuredTemplatePath;
        this.objectMapper = new ObjectMapper();
    }

    public ExcelExportResponse export(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);

        List<CosmicResult> results = readCosmicResults(Paths.get(metadata.getCosmicResultsPath()));
        List<FunctionSplitRow> splitRows = readOrCreateSplitRows(metadata, results);
        RequirementSourceOrder.sortRows(splitRows, readExtractedFunctions(metadata));
        RuleValidationResponse validation = ruleValidationService.validate(splitRows);
        ruleValidationService.ensureReadyForExport(validation, "导出送审 Excel");
        Path templatePath = resolveTemplatePath(zh("\u9644\u4ef63"));
        Path businessTemplatePath = resolveTemplatePath(zh("\u9644\u4ef62"));
        Path projectOutputDir = outputBaseDir.resolve(projectId);
        Path outputPath = projectOutputDir.resolve(buildExcelOutputFileName(metadata));
        Path businessOutputPath = projectOutputDir.resolve(buildBusinessRequirementOutputFileName(metadata));
        Path actualOutputPath = outputPath;
        Path actualBusinessOutputPath = businessOutputPath;

        int movementRows;
        double totalCfp = 0;
        try {
            Files.createDirectories(projectOutputDir);
            try (InputStream inputStream = Files.newInputStream(templatePath);
                 Workbook workbook = new XSSFWorkbook(inputStream)) {
                Sheet sheet = workbook.getSheet(zh("\u0032\u3001\u529f\u80fd\u70b9\u62c6\u5206\u8868"));
                if (sheet == null) {
                    sheet = workbook.getSheetAt(2);
                }
                movementRows = fillSheet(sheet, metadata, splitRows, validation);
                for (FunctionSplitRow row : splitRows) {
                    totalCfp += row.getCfp();
                }
                actualOutputPath = writableOutputPath(outputPath);
                try (OutputStream outputStream = Files.newOutputStream(actualOutputPath)) {
                    workbook.write(outputStream);
                }
            }
            try (InputStream inputStream = Files.newInputStream(businessTemplatePath);
                 Workbook workbook = new XSSFWorkbook(inputStream)) {
                fillBusinessRequirementSheet(workbook, metadata, splitRows);
                actualBusinessOutputPath = writableOutputPath(businessOutputPath);
                try (OutputStream outputStream = Files.newOutputStream(actualBusinessOutputPath)) {
                    workbook.write(outputStream);
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("Export Excel failed: " + ex.getClass().getSimpleName() + " " + ex.getMessage(), ex);
        }

        metadata.setExcelOutputPath(actualOutputPath.toString());
        metadata.setBusinessRequirementOutputPath(actualBusinessOutputPath.toString());
        metadata.setMaterialArchiveOutputPath(null);
        metadata.setStatus("EXCEL_EXPORTED");
        metadata.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
        fileStorageService.saveMetadata(metadata);

        ExcelExportResponse response = new ExcelExportResponse();
        response.setProjectId(projectId);
        response.setExcelOutputPath(actualOutputPath.toString());
        response.setBusinessRequirementOutputPath(actualBusinessOutputPath.toString());
        response.setFunctionCount(results.size());
        response.setMovementRowCount(movementRows);
        response.setTotalCfp(totalCfp);
        response.setStatus(metadata.getStatus());
        return response;
    }

    /**
     * Builds the two project-level spreadsheets from one already-reviewed aggregate row set.
     * This deliberately does not mutate any individual requirement metadata.
     */
    public ProjectExcelExportResult exportProjectMaterial(String materialId,
                                                          String projectName,
                                                          List<FunctionSplitRow> rows,
                                                          Path outputDir) {
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException("项目级送审材料没有可导出的功能点拆分行。");
        }
        RuleValidationResponse validation = ruleValidationService.validate(rows);
        ruleValidationService.ensureReadyForExport(validation, "导出项目级送审 Excel");

        ProjectMetadata metadata = projectMaterialMetadata(materialId, projectName);
        Path attachment3Template = resolveTemplatePath(zh("\u9644\u4ef63"));
        Path attachment2Template = resolveTemplatePath(zh("\u9644\u4ef62"));
        Path attachment3 = outputDir.resolve(zh("\u9644\u4ef63-") + sanitizeFileName(projectName)
                + zh("\u529f\u80fd\u70b9\u62c6\u5206\u8868V1.0.xlsx"));
        Path attachment2 = outputDir.resolve(zh("\u9644\u4ef62-") + sanitizeFileName(projectName)
                + zh("\u4e1a\u52a1\u9700\u6c42\u4fe1\u606f\u6c47\u603b\u8868V1.0.xlsx"));

        int movementRows;
        try {
            Files.createDirectories(outputDir);
            try (InputStream inputStream = Files.newInputStream(attachment3Template);
                 Workbook workbook = new XSSFWorkbook(inputStream)) {
                Sheet sheet = workbook.getSheet(zh("\u0032\u3001\u529f\u80fd\u70b9\u62c6\u5206\u8868"));
                if (sheet == null) {
                    sheet = workbook.getSheetAt(2);
                }
                movementRows = fillSheet(sheet, metadata, rows, validation);
                try (OutputStream outputStream = Files.newOutputStream(attachment3)) {
                    workbook.write(outputStream);
                }
            }
            try (InputStream inputStream = Files.newInputStream(attachment2Template);
                 Workbook workbook = new XSSFWorkbook(inputStream)) {
                fillBusinessRequirementSheet(workbook, metadata, rows);
                try (OutputStream outputStream = Files.newOutputStream(attachment2)) {
                    workbook.write(outputStream);
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("导出项目级送审 Excel 失败：" + ex.getMessage(), ex);
        }

        ProjectExcelExportResult result = new ProjectExcelExportResult();
        result.attachment2Path = attachment2;
        result.attachment3Path = attachment3;
        result.requirementCount = groupBusinessRequirements(metadata, rows).size();
        result.movementRowCount = movementRows;
        for (FunctionSplitRow row : rows) {
            result.totalCfp += row.getCfp();
        }
        return result;
    }

    private ProjectMetadata projectMaterialMetadata(String materialId, String projectName) {
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(materialId);
        metadata.setName(projectName);
        metadata.setSourceFileName(projectName + ".docx");
        metadata.setFileType("docx");
        return metadata;
    }

    public static class ProjectExcelExportResult {
        private Path attachment2Path;
        private Path attachment3Path;
        private int requirementCount;
        private int movementRowCount;
        private double totalCfp;

        public Path getAttachment2Path() { return attachment2Path; }
        public Path getAttachment3Path() { return attachment3Path; }
        public int getRequirementCount() { return requirementCount; }
        public int getMovementRowCount() { return movementRowCount; }
        public double getTotalCfp() { return totalCfp; }
    }

    private void validateMetadata(ProjectMetadata metadata) {
        if (metadata == null) {
            throw new BusinessException("Project metadata is empty");
        }
        if (!StringUtils.hasText(metadata.getCosmicResultsPath())) {
            throw new BusinessException("Project has not completed COSMIC analysis yet");
        }
    }

    private String buildExcelOutputFileName(ProjectMetadata metadata) {
        String sourceName = sourceBaseName(metadata);
        return zh("\u9644\u4ef63-") + sourceName + zh("\u7684\u9700\u6c42\u529f\u80fd\u70b9\u62c6\u5206\u8868V1.0.xlsx");
    }

    private String buildBusinessRequirementOutputFileName(ProjectMetadata metadata) {
        String sourceName = sourceBaseName(metadata);
        return zh("\u9644\u4ef62-") + sourceName + zh("\u7684\u4e1a\u52a1\u9700\u6c42\u4fe1\u606f\u6c47\u603b\u8868V1.0.xlsx");
    }

    private Path writableOutputPath(Path preferredPath) throws IOException {
        if (!Files.exists(preferredPath)) {
            return preferredPath;
        }
        return uniqueOutputPath(preferredPath);
    }

    private Path uniqueOutputPath(Path preferredPath) {
        String fileName = preferredPath.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String extension = dotIndex > 0 ? fileName.substring(dotIndex) : "";
        String suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return preferredPath.resolveSibling(baseName + "-" + suffix + extension);
    }

    private String sourceBaseName(ProjectMetadata metadata) {
        String name = metadata.getSourceFileName();
        if (!StringUtils.hasText(name)) {
            name = metadata.getName();
        }
        if (!StringUtils.hasText(name)) {
            name = metadata.getId();
        }
        name = Paths.get(name).getFileName().toString();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            name = name.substring(0, dotIndex);
        }
        return sanitizeFileName(name);
    }

    private String sanitizeFileName(String name) {
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return StringUtils.hasText(sanitized) ? sanitized : "未命名项目";
    }

    private List<CosmicResult> readCosmicResults(Path path) {
        if (!Files.exists(path)) {
            throw new BusinessException("COSMIC results file not found: " + path);
        }
        try {
            return objectMapper.readValue(path.toFile(), new TypeReference<List<CosmicResult>>() {
            });
        } catch (IOException ex) {
            throw new BusinessException("Read COSMIC results failed: " + path, ex);
        }
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

    private List<FunctionSplitRow> readOrCreateSplitRows(ProjectMetadata metadata, List<CosmicResult> results) {
        if (StringUtils.hasText(metadata.getFunctionSplitRowsPath()) && Files.exists(Paths.get(metadata.getFunctionSplitRowsPath()))) {
            try {
                List<FunctionSplitRow> rows = objectMapper.readValue(Paths.get(metadata.getFunctionSplitRowsPath()).toFile(),
                        new TypeReference<List<FunctionSplitRow>>() {
                        });
                normalizeSplitRows(metadata, rows);
                return rows;
            } catch (IOException ex) {
                throw new BusinessException("Read function split rows failed", ex);
            }
        }
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        for (CosmicResult result : results) {
            rows.addAll(toSplitRows(metadata, result));
        }
        normalizeSplitRows(metadata, rows);
        return rows;
    }

    private Path resolveTemplatePath(String templatePrefix) {
        if (StringUtils.hasText(configuredTemplatePath)) {
            Path path = Paths.get(configuredTemplatePath);
            if (Files.exists(path) && path.getFileName().toString().startsWith(templatePrefix)) {
                return path;
            }
        }

        Path template = findTemplateInDirectory(Paths.get("templates"), templatePrefix);
        if (template != null) {
            return template;
        }
        template = findTemplateInDirectory(Paths.get(".."), templatePrefix);
        if (template != null) {
            return template;
        }
        throw new BusinessException("Excel template not found: " + templatePrefix);
    }

    private Path findTemplateInDirectory(Path directory, String templatePrefix) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.xlsx")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.startsWith(templatePrefix) && !name.startsWith("~$")) {
                    return path;
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("Find Excel template failed", ex);
        }
        return null;
    }

    private int fillSheet(Sheet sheet, ProjectMetadata metadata, List<FunctionSplitRow> rows,
                          RuleValidationResponse validation) {
        clearTemplateData(sheet);
        prepareRiskReasonColumn(sheet);

        int rowIndex = FIRST_SPLIT_DATA_ROW;
        int movementRows = 0;
        Map<Short, CellStyle> styleCache = new HashMap<Short, CellStyle>();
        Map<Integer, String> validationRiskReasonsByRow = validationRiskReasonsByRow(
                validation, rows.size(), FIRST_SPLIT_DATA_ROW);
        for (FunctionSplitRow splitRow : rows) {
            Row row = getOrCreateRow(sheet, rowIndex);
            copyRowStyle(sheet, 5 + Math.min(movementRows, 2), rowIndex, styleCache);
            copyCellStyle(row, 10, RISK_REASON_COLUMN);
            setCell(row, 0, splitRow.getProjectName());
            setCell(row, 1, splitRow.getLevel1Module());
            setCell(row, 2, splitRow.getLevel2Module());
            setCell(row, 3, splitRow.getLevel3Module());
            setCell(row, 4, splitRow.getFunctionalUser());
            setCell(row, 5, splitRow.getTriggerEvent());
            setCell(row, 6, splitRow.getFunctionProcess());
            setCell(row, 7, splitRow.getSubProcessDescription());
            setCell(row, 8, splitRow.getMovementType());
            setCell(row, 9, splitRow.getDataGroup());
                setCell(row, 10, splitRow.getDataAttributes());
                setCell(row, 11, splitRow.getReuseDegree());
                String formula = "IF(L" + (rowIndex + 1) + "=\"" + zh("\u65b0\u589e") + "\",1,IF(L"
                        + (rowIndex + 1) + "=\"" + zh("\u590d\u7528") + "\",1/3,IF(L"
                        + (rowIndex + 1) + "=\"" + zh("\u5229\u65e7") + "\",0)))";
                setFormula(row, CFP_COLUMN, formula);
                setOptionalCell(row, RISK_REASON_COLUMN, validationRiskReasonsByRow.get(movementRows + 1));
                rowIndex++;
                movementRows++;
            }

            int lastDataRow = rowIndex - 1;
        applyDataBorders(sheet, FIRST_SPLIT_DATA_ROW, lastDataRow, 0, STANDARD_LAST_EXPORT_COLUMN);
        Map<Integer, String> validationSeverityByRow = validationSeverityByRow(validation, rows.size());
        mergeHierarchyColumns(sheet, rows, FIRST_SPLIT_DATA_ROW, lastDataRow);
        applyValidationRiskFills(sheet, FIRST_SPLIT_DATA_ROW, lastDataRow, validationSeverityByRow);
        applyRiskReasonFont(sheet, 1, lastDataRow);
        addReuseDegreeValidation(sheet, FIRST_SPLIT_DATA_ROW, rowIndex - 1);
        return movementRows;
    }

    private void prepareRiskReasonColumn(Sheet sheet) {
        sheet.setColumnHidden(RISK_REASON_COLUMN, false);
        sheet.setColumnHidden(RISK_REASON_COLUMN + 1, true);
        sheet.setColumnWidth(RISK_REASON_COLUMN, Math.max(sheet.getColumnWidth(10), 40 * 256));

        extendTitleMergeToRiskReasonColumn(sheet);
        copyHeaderCellStyle(sheet, 1, CFP_COLUMN, RISK_REASON_COLUMN);
        setCell(getOrCreateRow(sheet, 1), RISK_REASON_COLUMN, zh("\u5ba1\u6838\u4fe1\u606f"));
        copyHeaderCellStyle(sheet, 2, CFP_COLUMN, RISK_REASON_COLUMN);
        copyHeaderCellStyle(sheet, 3, CFP_COLUMN, RISK_REASON_COLUMN);
        setCell(getOrCreateRow(sheet, 2), RISK_REASON_COLUMN,
                zh("\u98ce\u9669\u539f\u56e0\uff08\u63d0\u4ea4\u9001\u5ba1\u524d\u52a1\u5fc5\u5220\u9664\uff09"));
        setOptionalCell(getOrCreateRow(sheet, 3), RISK_REASON_COLUMN, null);
        CellRangeAddress headerRegion = new CellRangeAddress(2, 3, RISK_REASON_COLUMN, RISK_REASON_COLUMN);
        sheet.addMergedRegion(headerRegion);
        RegionUtil.setBorderTop(BorderStyle.THIN, headerRegion, sheet);
        RegionUtil.setBorderRight(BorderStyle.THIN, headerRegion, sheet);
        RegionUtil.setBorderBottom(BorderStyle.THIN, headerRegion, sheet);
        RegionUtil.setBorderLeft(BorderStyle.THIN, headerRegion, sheet);

        copyHeaderCellStyle(sheet, 4, CFP_COLUMN, RISK_REASON_COLUMN);
        setCell(getOrCreateRow(sheet, 4), RISK_REASON_COLUMN,
                zh("\u89c4\u5219\u521d\u5ba1\u53d1\u73b0\u7684\u98ce\u9669\u539f\u56e0\uff0c\u591a\u4e2a\u539f\u56e0\u5206\u884c\u5c55\u793a\u3002"));
    }

    private void extendTitleMergeToRiskReasonColumn(Sheet sheet) {
        for (int index = 0; index < sheet.getNumMergedRegions(); index++) {
            CellRangeAddress region = sheet.getMergedRegion(index);
            if (region.getFirstRow() == 0 && region.getLastRow() == 0
                    && region.getFirstColumn() == 0 && region.getLastColumn() == CFP_COLUMN) {
                sheet.removeMergedRegion(index);
                sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, RISK_REASON_COLUMN));
                return;
            }
        }
    }

    private void copyHeaderCellStyle(Sheet sheet, int rowIndex, int sourceColumn, int targetColumn) {
        Row row = getOrCreateRow(sheet, rowIndex);
        copyCellStyle(row, sourceColumn, targetColumn);
    }

    private void copyCellStyle(Row row, int sourceColumn, int targetColumn) {
        Cell source = row.getCell(sourceColumn);
        Cell target = row.getCell(targetColumn);
        if (target == null) {
            target = row.createCell(targetColumn);
        }
        if (source != null) {
            target.setCellStyle(source.getCellStyle());
        }
    }

    private void fillBusinessRequirementSheet(Workbook workbook, ProjectMetadata metadata, List<FunctionSplitRow> rows) {
        Sheet sheet = workbook.getSheet(zh("\u529f\u80fd\u9700\u6c42\u5217\u8868"));
        if (sheet == null) {
            sheet = workbook.getSheetAt(0);
        }
        clearBusinessRequirementData(sheet);

        Map<Short, CellStyle> styleCache = new HashMap<Short, CellStyle>();
        List<BusinessRequirementGroup> groups = groupBusinessRequirements(metadata, rows);
        int firstDataRow = 10;
        double totalCfp = 0;
        for (int i = 0; i < groups.size(); i++) {
            BusinessRequirementGroup group = groups.get(i);
            int rowIndex = firstDataRow + i;
            Row row = getOrCreateRow(sheet, rowIndex);
            copyRowStyle(sheet, 10, rowIndex, styleCache);
            totalCfp += group.cfp;

            setCell(row, 0, String.format("%03d", i + 1));
            setCell(row, 1, projectName(metadata));
            setCell(row, 2, "");
            setCell(row, 3, group.level1);
            setCell(row, 4, group.level2);
            setCell(row, 5, group.level3);
            setCell(row, 6, group.changeType);
            setNumericCell(row, 7, group.cfp);
            setNumericCell(row, 8, group.cfp);
        }

        Row summaryRow = getOrCreateRow(sheet, 3);
        setNumericCell(summaryRow, 0, 1);
        setCell(summaryRow, 1, projectName(metadata));
        setCell(summaryRow, 2, "");
        setNumericCell(summaryRow, 3, groups.size());
        setNumericCell(summaryRow, 4, totalCfp);

        Row totalRow = getOrCreateRow(sheet, 5);
        setNumericCell(totalRow, 3, groups.size());
        setNumericCell(totalRow, 4, totalCfp);

        int lastDataRow = firstDataRow + groups.size() - 1;
        applyDataBorders(sheet, firstDataRow, lastDataRow, 0, 8);
    }

    private List<BusinessRequirementGroup> groupBusinessRequirements(ProjectMetadata metadata, List<FunctionSplitRow> rows) {
        Map<String, BusinessRequirementGroup> groupMap = new LinkedHashMap<String, BusinessRequirementGroup>();
        for (FunctionSplitRow row : rows) {
            String level1 = safeText(row.getLevel1Module());
            String level2 = safeText(row.getLevel2Module());
            String level3 = safeText(row.getLevel3Module());
            String key = level1 + "\u001f" + level2 + "\u001f" + level3;
            BusinessRequirementGroup group = groupMap.get(key);
            if (group == null) {
                group = new BusinessRequirementGroup();
                group.level1 = level1;
                group.level2 = level2;
                group.level3 = level3;
                group.changeType = businessChangeType(row.getReuseDegree());
                groupMap.put(key, group);
            }
            if (zh("\u65b0\u589e").equals(row.getReuseDegree())) {
                group.changeType = zh("\u65b0\u589e");
            }
            group.cfp += row.getCfp();
        }
        return new ArrayList<BusinessRequirementGroup>(groupMap.values());
    }

    private String projectName(ProjectMetadata metadata) {
        if (StringUtils.hasText(metadata.getName())) {
            return metadata.getName();
        }
        return sourceBaseName(metadata);
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String businessChangeType(String reuseDegree) {
        return zh("\u65b0\u589e").equals(reuseDegree) ? zh("\u65b0\u589e") : zh("\u4f18\u5316");
    }

    private void clearBusinessRequirementData(Sheet sheet) {
        int lastRowToClear = Math.min(Math.max(sheet.getLastRowNum(), 120), TEMPLATE_CLEAR_ROW_LIMIT);
        for (int r = 3; r <= lastRowToClear; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = 0; c <= 8; c++) {
                Cell cell = row.getCell(c);
                boolean summaryPlaceholder = (r == 3 && c <= 6)
                        || (r == 4 && c <= 6)
                        || (r == 5 && (c == 0 || (c >= 2 && c <= 5)))
                        || r == 8;
                if (cell != null && (r >= 10 || summaryPlaceholder)) {
                    cell.setBlank();
                }
            }
        }
    }

    private void applyDataBorders(Sheet sheet, int firstRow, int lastRow, int firstCol, int lastCol) {
        if (lastRow < firstRow) {
            return;
        }
        Workbook workbook = sheet.getWorkbook();
        Map<Short, CellStyle> borderStyleCache = new HashMap<Short, CellStyle>();
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            Row row = getOrCreateRow(sheet, rowIndex);
            for (int colIndex = firstCol; colIndex <= lastCol; colIndex++) {
                Cell cell = row.getCell(colIndex);
                if (cell == null) {
                    cell = row.createCell(colIndex);
                }
                cell.setCellStyle(borderedStyle(workbook, cell.getCellStyle(), borderStyleCache));
            }
        }
    }

    private CellStyle borderedStyle(Workbook workbook, CellStyle sourceStyle, Map<Short, CellStyle> borderStyleCache) {
        short sourceIndex = sourceStyle == null ? -1 : sourceStyle.getIndex();
        CellStyle cached = borderStyleCache.get(sourceIndex);
        if (cached != null) {
            return cached;
        }

        CellStyle style = workbook.createCellStyle();
        if (sourceStyle != null) {
            style.cloneStyleFrom(sourceStyle);
        }
        applyThinBorders(style);
        borderStyleCache.put(sourceIndex, style);
        return style;
    }

    private void mergeHierarchyColumns(Sheet sheet, List<FunctionSplitRow> rows, int firstRow, int lastRow) {
        mergeSameContentColumn(sheet, rows, firstRow, lastRow, 0, -1);
        mergeSameContentColumn(sheet, rows, firstRow, lastRow, 1, 0);
        mergeSameContentColumn(sheet, rows, firstRow, lastRow, 2, 1);
        mergeSameContentColumn(sheet, rows, firstRow, lastRow, 3, 2);
        mergeSameContentColumn(sheet, rows, firstRow, lastRow, 4, 3);
        mergeSameContentColumn(sheet, rows, firstRow, lastRow, 5, 4);
        mergeSameContentColumn(sheet, rows, firstRow, lastRow, 6, 5);
    }

    private void mergeSameContentColumn(Sheet sheet, List<FunctionSplitRow> rows, int firstRow, int lastRow,
                                        int colIndex, int parentColIndex) {
        if (lastRow < firstRow) {
            return;
        }
        int groupStart = firstRow;
        String previous = rowValue(rows, 0, colIndex);
        String previousParent = parentKey(rows, 0, parentColIndex);
        for (int rowIndex = firstRow + 1; rowIndex <= lastRow + 1; rowIndex++) {
            int dataIndex = rowIndex - firstRow;
            String current = rowIndex <= lastRow ? rowValue(rows, dataIndex, colIndex) : null;
            String currentParent = rowIndex <= lastRow ? parentKey(rows, dataIndex, parentColIndex) : null;
            if (previous != null && previous.equals(current) && previousParent.equals(currentParent)) {
                continue;
            }
            mergeRegionIfNeeded(sheet, groupStart, rowIndex - 1, colIndex, previous);
            groupStart = rowIndex;
            previous = current;
            previousParent = currentParent == null ? "" : currentParent;
        }
    }

    private Map<Integer, String> validationSeverityByRow(RuleValidationResponse validation, int rowCount) {
        Map<Integer, String> severities = new HashMap<Integer, String>();
        if (validation == null || validation.getIssues() == null) {
            return severities;
        }
        for (RuleValidationIssue issue : validation.getIssues()) {
            if (issue == null || issue.getRowIndex() < 1 || issue.getRowIndex() > rowCount) {
                continue;
            }
            String severity = issue.getSeverity();
            if (!VALIDATION_ERROR.equals(severity) && !VALIDATION_RISK.equals(severity)) {
                continue;
            }
            String current = severities.get(issue.getRowIndex());
            if (current == null || VALIDATION_ERROR.equals(severity)) {
                severities.put(issue.getRowIndex(), severity);
            }
        }
        return severities;
    }

    private Map<Integer, String> validationRiskReasonsByRow(RuleValidationResponse validation,
                                                            int rowCount,
                                                            int firstDataRowIndex) {
        Map<Integer, LinkedHashSet<String>> reasonsByRow = new LinkedHashMap<Integer, LinkedHashSet<String>>();
        if (validation == null || validation.getIssues() == null) {
            return new LinkedHashMap<Integer, String>();
        }
        for (RuleValidationIssue issue : validation.getIssues()) {
            if (issue == null || issue.getRowIndex() < 1 || issue.getRowIndex() > rowCount
                    || (!VALIDATION_ERROR.equals(issue.getSeverity()) && !VALIDATION_RISK.equals(issue.getSeverity()))) {
                continue;
            }
            String message = excelRowReferences(safeText(issue.getMessage()), firstDataRowIndex);
            if (!StringUtils.hasText(message)) {
                continue;
            }
            String field = safeText(issue.getField());
            String reason = StringUtils.hasText(field) ? "[" + field + "] " + message : message;
            LinkedHashSet<String> reasons = reasonsByRow.get(issue.getRowIndex());
            if (reasons == null) {
                reasons = new LinkedHashSet<String>();
                reasonsByRow.put(issue.getRowIndex(), reasons);
            }
            reasons.add(reason);
        }
        Map<Integer, String> result = new LinkedHashMap<Integer, String>();
        for (Map.Entry<Integer, LinkedHashSet<String>> entry : reasonsByRow.entrySet()) {
            result.put(entry.getKey(), String.join("\n", entry.getValue()));
        }
        return result;
    }

    String excelRowReferences(String message, int firstDataRowIndex) {
        if (!StringUtils.hasText(message)) {
            return message;
        }
        Matcher matcher = LOGICAL_ROW_REFERENCE.matcher(message);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            int logicalRow;
            try {
                logicalRow = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ex) {
                continue;
            }
            int excelRow = firstDataRowIndex + logicalRow;
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    "Excel 第 " + excelRow + " 行（拆分数据第 " + logicalRow + " 条）"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void applyValidationRiskFills(Sheet sheet, int firstRow, int lastRow,
                                          Map<Integer, String> validationSeverityByRow) {
        if (lastRow < firstRow || validationSeverityByRow.isEmpty()) {
            return;
        }
        Workbook workbook = sheet.getWorkbook();
        Map<String, CellStyle> fillStyleCache = new HashMap<String, CellStyle>();
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            String severity = validationSeverityByRow.get(rowIndex - firstRow + 1);
            if (severity == null) {
                continue;
            }
            Row row = getOrCreateRow(sheet, rowIndex);
            for (int colIndex = FIRST_NON_MERGED_EXPORT_COLUMN;
                 colIndex <= STANDARD_LAST_EXPORT_COLUMN; colIndex++) {
                Cell cell = row.getCell(colIndex);
                if (cell == null) {
                    cell = row.createCell(colIndex);
                }
                cell.setCellStyle(validationRiskStyle(workbook, cell.getCellStyle(), severity, fillStyleCache));
            }
        }
    }

    private CellStyle validationRiskStyle(Workbook workbook, CellStyle sourceStyle, String severity,
                                          Map<String, CellStyle> fillStyleCache) {
        short sourceIndex = sourceStyle == null ? -1 : sourceStyle.getIndex();
        String cacheKey = sourceIndex + ":" + severity;
        CellStyle cached = fillStyleCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        CellStyle style = workbook.createCellStyle();
        if (sourceStyle != null) {
            style.cloneStyleFrom(sourceStyle);
        }
        if (style instanceof XSSFCellStyle) {
            byte[] rgb = VALIDATION_ERROR.equals(severity)
                    ? VALIDATION_ERROR_FILL_RGB : VALIDATION_RISK_FILL_RGB;
            ((XSSFCellStyle) style).setFillForegroundColor(
                    new XSSFColor(rgb, new DefaultIndexedColorMap()));
        } else {
            style.setFillForegroundColor(VALIDATION_ERROR.equals(severity)
                    ? IndexedColors.ROSE.getIndex() : IndexedColors.LIGHT_YELLOW.getIndex());
        }
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        fillStyleCache.put(cacheKey, style);
        return style;
    }

    private void applyRiskReasonFont(Sheet sheet, int firstRow, int lastRow) {
        Workbook workbook = sheet.getWorkbook();
        Map<Short, CellStyle> styleCache = new HashMap<Short, CellStyle>();
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            Cell cell = row == null ? null : row.getCell(RISK_REASON_COLUMN);
            if (cell == null) {
                continue;
            }
            CellStyle sourceStyle = cell.getCellStyle();
            short sourceIndex = sourceStyle.getIndex();
            CellStyle emphasizedStyle = styleCache.get(sourceIndex);
            if (emphasizedStyle == null) {
                emphasizedStyle = workbook.createCellStyle();
                emphasizedStyle.cloneStyleFrom(sourceStyle);
                emphasizedStyle.setFont(createRedBoldFont(workbook, sourceStyle));
                styleCache.put(sourceIndex, emphasizedStyle);
            }
            cell.setCellStyle(emphasizedStyle);
        }
    }

    private Font createRedBoldFont(Workbook workbook, CellStyle sourceStyle) {
        Font sourceFont = workbook.getFontAt(sourceStyle.getFontIndexAsInt());
        Font font = workbook.createFont();
        font.setFontName(sourceFont.getFontName());
        font.setFontHeight(sourceFont.getFontHeight());
        font.setBold(true);
        font.setItalic(sourceFont.getItalic());
        font.setUnderline(sourceFont.getUnderline());
        font.setStrikeout(sourceFont.getStrikeout());
        font.setTypeOffset(sourceFont.getTypeOffset());
        font.setCharSet(sourceFont.getCharSet());
        font.setColor(IndexedColors.RED.getIndex());
        return font;
    }

    private void mergeRegionIfNeeded(Sheet sheet, int firstRow, int lastRow, int colIndex, String value) {
        if (lastRow <= firstRow || !StringUtils.hasText(value)) {
            return;
        }
        CellRangeAddress region = new CellRangeAddress(firstRow, lastRow, colIndex, colIndex);
        sheet.addMergedRegion(region);
        for (int rowIndex = firstRow + 1; rowIndex <= lastRow; rowIndex++) {
            Cell cell = getOrCreateRow(sheet, rowIndex).getCell(colIndex);
            if (cell != null) {
                cell.setBlank();
            }
        }
        RegionUtil.setBorderTop(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderRight(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderBottom(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderLeft(BorderStyle.THIN, region, sheet);
    }

    private String parentKey(List<FunctionSplitRow> rows, int dataIndex, int parentColIndex) {
        if (parentColIndex < 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int colIndex = 0; colIndex <= parentColIndex; colIndex++) {
            if (colIndex > 0) {
                builder.append('\u001f');
            }
            builder.append(rowValue(rows, dataIndex, colIndex));
        }
        return builder.toString();
    }

    private String rowValue(List<FunctionSplitRow> rows, int dataIndex, int colIndex) {
        if (dataIndex < 0 || dataIndex >= rows.size()) {
            return "";
        }
        FunctionSplitRow row = rows.get(dataIndex);
        String value;
        if (colIndex == 0) {
            value = row.getProjectName();
        } else if (colIndex == 1) {
            value = row.getLevel1Module();
        } else if (colIndex == 2) {
            value = row.getLevel2Module();
        } else if (colIndex == 3) {
            value = row.getLevel3Module();
        } else if (colIndex == 4) {
            value = row.getFunctionalUser();
        } else if (colIndex == 5) {
            value = row.getTriggerEvent();
        } else if (colIndex == 6) {
            value = row.getFunctionProcess();
        } else {
            value = "";
        }
        return value == null ? "" : value.trim();
    }

    private void clearTemplateData(Sheet sheet) {
        removeTemplateConditionalFormatting(sheet);
        List<CellRangeAddress> mergedToRemove = new ArrayList<CellRangeAddress>();
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress region = sheet.getMergedRegion(i);
            if (region.getFirstRow() >= 5) {
                mergedToRemove.add(region);
            }
        }
        for (CellRangeAddress region : mergedToRemove) {
            int index = findMergedRegionIndex(sheet, region);
            if (index >= 0) {
                sheet.removeMergedRegion(index);
            }
        }

        int lastRowToClear = Math.min(Math.max(sheet.getLastRowNum(), 120), TEMPLATE_CLEAR_ROW_LIMIT);
        for (int r = 5; r <= lastRowToClear; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = 0; c <= 14; c++) {
                Cell cell = row.getCell(c);
                if (cell != null) {
                    cell.setBlank();
                }
            }
        }
    }

    private void removeTemplateConditionalFormatting(Sheet sheet) {
        SheetConditionalFormatting formatting = sheet.getSheetConditionalFormatting();
        for (int index = formatting.getNumConditionalFormattings() - 1; index >= 0; index--) {
            formatting.removeConditionalFormatting(index);
        }
    }

    private int findMergedRegionIndex(Sheet sheet, CellRangeAddress target) {
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            if (sheet.getMergedRegion(i).formatAsString().equals(target.formatAsString())) {
                return i;
            }
        }
        return -1;
    }

    private void copyRowStyle(Sheet sheet, int sourceRowIndex, int targetRowIndex, Map<Short, CellStyle> styleCache) {
        Row source = sheet.getRow(sourceRowIndex);
        Row target = getOrCreateRow(sheet, targetRowIndex);
        if (source == null) {
            return;
        }
        target.setHeight(source.getHeight());
        for (int c = 0; c <= STANDARD_LAST_EXPORT_COLUMN; c++) {
            Cell sourceCell = source.getCell(c);
            Cell targetCell = target.getCell(c);
            if (targetCell == null) {
                targetCell = target.createCell(c);
            }
            if (sourceCell != null) {
                CellStyle style = cleanContentStyle(sheet.getWorkbook(), sourceCell.getCellStyle(), styleCache);
                targetCell.setCellStyle(style);
            }
            targetCell.setBlank();
        }
    }

    private CellStyle cleanContentStyle(Workbook workbook, CellStyle sourceStyle, Map<Short, CellStyle> styleCache) {
        if (sourceStyle == null) {
            return null;
        }
        short sourceIndex = sourceStyle.getIndex();
        CellStyle cached = styleCache.get(sourceIndex);
        if (cached != null) {
            return cached;
        }

        CellStyle style = workbook.createCellStyle();
        style.cloneStyleFrom(sourceStyle);
        style.setFillForegroundColor(IndexedColors.WHITE.getIndex());
        style.setFillBackgroundColor(IndexedColors.WHITE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setFont(createBlackFont(workbook, sourceStyle));
        applyThinBorders(style);
        styleCache.put(sourceIndex, style);
        return style;
    }

    private void applyThinBorders(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
    }

    private Font createBlackFont(Workbook workbook, CellStyle sourceStyle) {
        Font sourceFont = workbook.getFontAt(sourceStyle.getFontIndexAsInt());
        Font font = workbook.createFont();
        font.setFontName(sourceFont.getFontName());
        font.setFontHeight(sourceFont.getFontHeight());
        font.setBold(sourceFont.getBold());
        font.setItalic(sourceFont.getItalic());
        font.setUnderline(sourceFont.getUnderline());
        font.setStrikeout(sourceFont.getStrikeout());
        font.setTypeOffset(sourceFont.getTypeOffset());
        font.setCharSet(sourceFont.getCharSet());
        font.setColor(IndexedColors.BLACK.getIndex());
        return font;
    }

    private Row getOrCreateRow(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        return row;
    }

    private void setCell(Row row, int col, String value) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            cell = row.createCell(col);
        }
        if (StringUtils.hasText(value)) {
            cell.setCellValue(value);
        } else {
            cell.setBlank();
        }
    }

    private void setOptionalCell(Row row, int col, String value) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            cell = row.createCell(col);
        }
        if (StringUtils.hasText(value)) {
            cell.setCellValue(value);
        } else {
            cell.setBlank();
        }
    }

    private void setNumericCell(Row row, int col, double value) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            cell = row.createCell(col);
        }
        cell.setCellValue(value);
    }

    private void setFormula(Row row, int col, String formula) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            cell = row.createCell(col);
        }
        cell.setCellFormula(formula);
    }

    private void addReuseDegreeValidation(Sheet sheet, int firstRow, int lastRow) {
        if (lastRow < firstRow) {
            return;
        }
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(new String[]{zh("\u65b0\u589e"), zh("\u590d\u7528"), zh("\u5229\u65e7")});
        CellRangeAddressList regions = new CellRangeAddressList(firstRow, lastRow, 11, 11);
        DataValidation validation = helper.createValidation(constraint, regions);
        validation.setSuppressDropDownArrow(true);
        sheet.addValidationData(validation);
    }

    private List<FunctionSplitRow> toSplitRows(ProjectMetadata metadata, CosmicResult result) {
        return FunctionSplitRowFactory.create(metadata, result);
    }

    private void normalizeSplitRows(ProjectMetadata metadata, List<FunctionSplitRow> rows) {
        String lastLevel1 = "";
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
            row.setReuseDegree(normalizeReuseDegree(row.getReuseDegree()));
            row.setCfp(cfpByReuse(row.getReuseDegree()));
            lastLevel1 = row.getLevel1Module();
            lastProcess = row.getFunctionProcess();
        }
    }

    private String normalizeReuseDegree(String reuseDegree) {
        String value = reuseDegree == null ? "" : reuseDegree.trim();
        if (zh("\u65b0\u589e").equals(value) || zh("\u590d\u7528").equals(value) || zh("\u5229\u65e7").equals(value)) {
            return value;
        }
        return zh("\u65b0\u589e");
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
        if ("E".equals(movementType)) {
            return "UI->UI";
        }
        if ("R".equals(movementType)) {
            if (isBackendRead(subProcessDescription, dataGroup, dataAttributes)) {
                return zh("\u540e\u53f0\u670d\u52a1->\u540e\u53f0\u670d\u52a1");
            }
            return zh("UI->\u540e\u53f0\u670d\u52a1");
        }
        if ("W".equals(movementType)) {
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

    private String zh(String value) {
        return value;
    }

    private static class BusinessRequirementGroup {
        private String level1;
        private String level2;
        private String level3;
        private String changeType;
        private double cfp;
    }
}
