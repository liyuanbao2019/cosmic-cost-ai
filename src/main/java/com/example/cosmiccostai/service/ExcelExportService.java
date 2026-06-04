package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.ExcelExportResponse;
import com.example.cosmiccostai.model.CosmicResult;
import com.example.cosmiccostai.model.FunctionSplitRow;
import com.example.cosmiccostai.model.ProjectMetadata;
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
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.RegionUtil;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExcelExportService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int TEMPLATE_CLEAR_ROW_LIMIT = 500;

    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;
    private final Path outputBaseDir;
    private final String configuredTemplatePath;

    public ExcelExportService(FileStorageService fileStorageService,
                              @Value("${cosmic.output.base-dir:output}") String outputBaseDir,
                              @Value("${cosmic.template.excel-path:}") String configuredTemplatePath) {
        this.fileStorageService = fileStorageService;
        this.outputBaseDir = Paths.get(outputBaseDir);
        this.configuredTemplatePath = configuredTemplatePath;
        this.objectMapper = new ObjectMapper();
    }

    public ExcelExportResponse export(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);

        List<CosmicResult> results = readCosmicResults(Paths.get(metadata.getCosmicResultsPath()));
        List<FunctionSplitRow> splitRows = readOrCreateSplitRows(metadata, results);
        Path templatePath = resolveTemplatePath();
        Path projectOutputDir = outputBaseDir.resolve(projectId);
        Path outputPath = projectOutputDir.resolve(buildExcelOutputFileName(metadata));
        Path actualOutputPath = outputPath;

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
                movementRows = fillSheet(sheet, metadata, splitRows);
                for (FunctionSplitRow row : splitRows) {
                    totalCfp += row.getCfp();
                }
                actualOutputPath = writableOutputPath(outputPath);
                try (OutputStream outputStream = Files.newOutputStream(actualOutputPath)) {
                    workbook.write(outputStream);
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("Export Excel failed: " + ex.getClass().getSimpleName() + " " + ex.getMessage(), ex);
        }

        metadata.setExcelOutputPath(actualOutputPath.toString());
        metadata.setStatus("EXCEL_EXPORTED");
        metadata.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
        fileStorageService.saveMetadata(metadata);

        ExcelExportResponse response = new ExcelExportResponse();
        response.setProjectId(projectId);
        response.setExcelOutputPath(actualOutputPath.toString());
        response.setFunctionCount(results.size());
        response.setMovementRowCount(movementRows);
        response.setTotalCfp(totalCfp);
        response.setStatus(metadata.getStatus());
        return response;
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

    private Path resolveTemplatePath() {
        if (StringUtils.hasText(configuredTemplatePath)) {
            Path path = Paths.get(configuredTemplatePath);
            if (Files.exists(path)) {
                return path;
            }
        }

        Path template = findTemplateInDirectory(Paths.get("templates"));
        if (template != null) {
            return template;
        }
        template = findTemplateInDirectory(Paths.get(".."));
        if (template != null) {
            return template;
        }
        throw new BusinessException("Excel template not found");
    }

    private Path findTemplateInDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.xlsx")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.startsWith(zh("\u9644\u4ef63")) && !name.startsWith("~$")) {
                    return path;
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("Find Excel template failed", ex);
        }
        return null;
    }

    private int fillSheet(Sheet sheet, ProjectMetadata metadata, List<FunctionSplitRow> rows) {
        clearTemplateData(sheet);

        int rowIndex = 5;
        int movementRows = 0;
        Map<Short, CellStyle> styleCache = new HashMap<Short, CellStyle>();
        for (FunctionSplitRow splitRow : rows) {
            Row row = getOrCreateRow(sheet, rowIndex);
            copyRowStyle(sheet, 5 + Math.min(movementRows, 2), rowIndex, styleCache);
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
                setFormula(row, 12, formula);
                setCell(row, 13, splitRow.getFunctionDescription());
                setCell(row, 14, splitRow.getSequenceDiagram());
                rowIndex++;
                movementRows++;
            }

            int lastDataRow = rowIndex - 1;
        writeExtendedHeaders(sheet);
        applyDataBorders(sheet, 5, lastDataRow, 0, 14);
        mergeHierarchyColumns(sheet, rows, 5, lastDataRow);
        addReuseDegreeValidation(sheet, 5, rowIndex - 1);
        return movementRows;
    }

    private void writeExtendedHeaders(Sheet sheet) {
        Row titleRow = getOrCreateRow(sheet, 2);
        Row helpRow = getOrCreateRow(sheet, 4);
        setCell(titleRow, 13, zh("\u529f\u80fd\u63cf\u8ff0"));
        setCell(titleRow, 14, zh("\u65f6\u5e8f\u56fe"));
        setCell(helpRow, 13, zh("\u8be5\u529f\u80fd\u8fc7\u7a0b\u7684\u4e1a\u52a1\u76ee\u7684\u3001\u5904\u7406\u5bf9\u8c61\u548c\u8f93\u51fa\u7ed3\u679c\u3002"));
        setCell(helpRow, 14, zh("\u683c\u5f0f\u4e3a\u201c\u53c2\u4e0e\u8005A->\u53c2\u4e0e\u8005B\u201d\uff0c\u591a\u4e2a\u6b65\u9aa4\u7528\u82f1\u6587\u5206\u53f7\u5206\u9694\u3002"));
        sheet.setColumnWidth(13, 48 * 256);
        sheet.setColumnWidth(14, 28 * 256);
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
    }

    private void mergeSameContentColumn(Sheet sheet, List<FunctionSplitRow> rows, int firstRow, int lastRow, int colIndex, int parentColIndex) {
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
        } else {
            value = "";
        }
        return value == null ? "" : value.trim();
    }

    private void clearTemplateData(Sheet sheet) {
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
        for (int c = 0; c <= 14; c++) {
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
        style.setFillPattern(FillPatternType.NO_FILL);
        style.setFillForegroundColor(IndexedColors.AUTOMATIC.getIndex());
        style.setFillBackgroundColor(IndexedColors.AUTOMATIC.getIndex());
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
        cell.setCellValue(value == null ? "" : value);
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
            row.setFunctionDescription(buildFunctionDescription(result.getDescription(), description, dataAttributes));
            row.setSequenceDiagram(defaultSequenceDiagram(type, description, dataGroup, dataAttributes));
            row.setReuseDegree(zh("\u65b0\u589e"));
            row.setCfp(1);
            rows.add(row);
        }
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
                row.setDataGroup(row.getFunctionProcess());
            }
            if (!StringUtils.hasText(row.getDataAttributes())) {
                row.setDataAttributes(zh("\u7f16\u53f7\u3001\u72b6\u6001\u3001\u65f6\u95f4"));
            }
            row.setSequenceDiagram(defaultSequenceDiagram(row.getMovementType(), row.getSubProcessDescription(),
                    row.getDataGroup(), row.getDataAttributes()));
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
        return value.equals(safe(row.getFunctionProcess())) || value.equals(safe(row.getSubProcessDescription()));
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
            return zh("\u540e\u53f0\u670d\u52a1->UI");
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

}
