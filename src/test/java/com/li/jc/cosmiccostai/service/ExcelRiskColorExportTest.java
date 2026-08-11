package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.cosmiccostai.dto.ExcelExportResponse;
import com.li.jc.cosmiccostai.dto.RuleValidationIssue;
import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.model.CosmicResult;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelRiskColorExportTest {

    private static final String PROJECT_ID = "RISK_COLOR_TEST";

    @Test
    void colorsOnlyNonMergedCellsAndExportsRiskReasonNextToCfp(@TempDir Path tempDir) throws Exception {
        Path verificationDir = verificationDir(tempDir);
        FileStorageService storageService = new FileStorageService(verificationDir.resolve("storage").toString());
        List<FunctionSplitRow> rows = rowsWithOneMaterialRisk();
        RuleValidationResponse validation = new RuleValidationService().validate(rows);
        assertEquals(0, validation.getErrorCount(), describe(validation));
        assertEquals(0, validation.getStructureRiskCount(), describe(validation));
        assertTrue(validation.getRiskCount() > 0, describe(validation));
        assertTrue(hasRiskOnRow(validation, 3), describe(validation));
        prepareProject(storageService, rows);

        ExcelExportService service = new ExcelExportService(storageService,
                verificationDir.resolve("output").toString(),
                "templates/附件3-XX项目功能点拆分表V1.0.xlsx");
        ExcelExportResponse response = service.export(PROJECT_ID);
        Path exportedPath = Paths.get(response.getExcelOutputPath());
        assertTrue(Files.exists(exportedPath));

        try (InputStream inputStream = Files.newInputStream(exportedPath);
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheet("2、功能点拆分表");
            if (sheet == null) {
                sheet = workbook.getSheetAt(2);
            }

            int cleanExcelRow = 5;
            int riskExcelRow = 7;
            for (int column = 7; column <= 13; column++) {
                Cell riskCell = sheet.getRow(riskExcelRow).getCell(column);
                assertEquals(FillPatternType.SOLID_FOREGROUND, riskCell.getCellStyle().getFillPattern());
                assertEquals("FFF2CC", rgbHex(riskCell), "column " + column);
                assertEquals(BorderStyle.THIN, riskCell.getCellStyle().getBorderTop(), "column " + column);

                Cell cleanCell = sheet.getRow(cleanExcelRow).getCell(column);
                assertNotEquals("FFF2CC", rgbHex(cleanCell), "column " + column);
            }
            for (int column = 0; column <= 6; column++) {
                assertTrue(isMergedAcrossRows(sheet, 5, 7, column),
                        "hierarchy column " + column + " must remain merged across all process rows");
                assertNotEquals("FFF2CC", rgbHex(sheet.getRow(5).getCell(column)), "column " + column);
            }
            assertEquals("RISK_COLOR_TEST", sheet.getRow(5).getCell(0).getStringCellValue());
            assertEquals("待确认", sheet.getRow(riskExcelRow).getCell(10).getStringCellValue());
            assertTrue(sheet.getRow(riskExcelRow).getCell(12).getCellFormula().contains("L8"));
            assertEquals("风险原因（提交送审前务必删除）",
                    sheet.getRow(2).getCell(13).getStringCellValue());
            assertFalse(sheet.isColumnHidden(13));
            Cell riskReasonCell = sheet.getRow(riskExcelRow).getCell(13);
            assertTrue(riskReasonCell.getStringCellValue().contains("待确认"));
            assertRedBold(workbook, sheet.getRow(1).getCell(13));
            assertRedBold(workbook, sheet.getRow(2).getCell(13));
            assertRedBold(workbook, sheet.getRow(4).getCell(13));
            assertRedBold(workbook, riskReasonCell);
            assertTrue(isMergedAcrossRows(sheet, 2, 3, 13), "risk reason header must match CFP header layout");
            assertTrue(isMergedAcrossRows(sheet, 0, 0, 0, 13), "title must extend through the risk reason column");
            assertEquals(0, sheet.getSheetConditionalFormatting().getNumConditionalFormattings(),
                    "legacy duplicate-value colors must not override unified validation colors");
        }
    }

    private Path verificationDir(Path tempDir) throws Exception {
        String configured = System.getProperty("riskColorVerificationDir");
        Path directory = configured == null || configured.trim().isEmpty()
                ? tempDir : Paths.get(configured);
        Files.createDirectories(directory);
        return directory;
    }

    private List<FunctionSplitRow> rowsWithOneMaterialRisk() {
        return Arrays.asList(
                row("E", "发起客户资料处理请求", "客户资料处理请求",
                        "客户编号、客户名称、客户类型、所属区域、生效日期、客户状态",
                        "功能用户->后台服务"),
                row("R", "读取客户资料", "客户资料",
                        "客户编号、客户名称、客户类型、所属区域、生效日期、客户状态",
                        "后台服务->后台服务"),
                row("X", "返回客户资料处理结果", "客户资料处理结果", "待确认",
                        "后台服务->功能用户"));
    }

    private FunctionSplitRow row(String movement, String description, String dataGroup,
                                 String attributes, String sequence) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setProjectName(PROJECT_ID);
        row.setLevel1Module("客户管理");
        row.setLevel2Module("客户资料");
        row.setLevel3Module("资料处理");
        row.setFunctionalUser("业务人员");
        row.setTriggerEvent("业务人员提交客户资料处理信息");
        row.setFunctionProcess("客户资料处理");
        row.setFunctionDescription("根据业务人员提交的信息处理客户资料并返回结果。");
        row.setSubProcessDescription(description);
        row.setMovementType(movement);
        row.setDataGroup(dataGroup);
        row.setDataAttributes(attributes);
        row.setSequenceDiagram(sequence);
        row.setReuseDegree("新增");
        row.setCfp(1D);
        return row;
    }

    private void prepareProject(FileStorageService storageService, List<FunctionSplitRow> rows) throws Exception {
        Path projectDir = storageService.resolveProjectDir(PROJECT_ID);
        Files.createDirectories(projectDir);
        Path resultsPath = projectDir.resolve("cosmic-results.json");
        Path rowsPath = projectDir.resolve("function-split-rows.json");

        CosmicResult result = new CosmicResult();
        result.setModule("客户管理");
        result.setFunction("客户资料处理");
        result.setDescription("根据业务人员提交的信息处理客户资料并返回结果。");
        result.setFunctionalUser("业务人员");
        result.setTriggerEvent("业务人员提交客户资料处理信息");
        result.setEntry(1);
        result.setRead(1);
        result.setExit(1);
        result.setCfp(3D);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.writeValue(resultsPath.toFile(), Collections.singletonList(result));
        objectMapper.writeValue(rowsPath.toFile(), rows);

        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(PROJECT_ID);
        metadata.setName(PROJECT_ID);
        metadata.setSourceFileName("risk-color-test.docx");
        metadata.setFileType("docx");
        metadata.setStatus("REVIEWED");
        metadata.setCosmicResultsPath(resultsPath.toString());
        metadata.setFunctionSplitRowsPath(rowsPath.toString());
        metadata.setCreatedAt("2026-08-10 00:00:00");
        metadata.setUpdatedAt("2026-08-10 00:00:00");
        storageService.saveMetadata(metadata);
    }

    private boolean hasRiskOnRow(RuleValidationResponse response, int rowIndex) {
        for (RuleValidationIssue issue : response.getIssues()) {
            if (rowIndex == issue.getRowIndex() && "RISK".equals(issue.getSeverity())) {
                return true;
            }
        }
        return false;
    }

    private boolean isMergedAcrossRows(Sheet sheet, int firstRow, int lastRow, int column) {
        return isMergedAcrossRows(sheet, firstRow, lastRow, column, column);
    }

    private boolean isMergedAcrossRows(Sheet sheet, int firstRow, int lastRow,
                                       int firstColumn, int lastColumn) {
        for (int index = 0; index < sheet.getNumMergedRegions(); index++) {
            org.apache.poi.ss.util.CellRangeAddress region = sheet.getMergedRegion(index);
            if (region.getFirstColumn() == firstColumn && region.getLastColumn() == lastColumn
                    && region.getFirstRow() <= firstRow && region.getLastRow() >= lastRow) {
                return true;
            }
        }
        return false;
    }

    private String rgbHex(Cell cell) {
        if (!(cell.getCellStyle() instanceof XSSFCellStyle)) {
            return String.valueOf(cell.getCellStyle().getFillForegroundColor());
        }
        org.apache.poi.xssf.usermodel.XSSFColor color =
                ((XSSFCellStyle) cell.getCellStyle()).getFillForegroundXSSFColor();
        if (color == null || color.getARGBHex() == null) {
            return "";
        }
        String argb = color.getARGBHex();
        return argb.length() > 6 ? argb.substring(argb.length() - 6) : argb;
    }

    private void assertRedBold(Workbook workbook, Cell cell) {
        Font font = workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt());
        assertTrue(font.getBold());
        assertEquals(IndexedColors.RED.getIndex(), font.getColor());
    }

    private String describe(RuleValidationResponse response) {
        StringBuilder builder = new StringBuilder();
        for (RuleValidationIssue issue : response.getIssues()) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(issue.getRowIndex()).append(':').append(issue.getField())
                    .append(':').append(issue.getMessage());
        }
        return builder.toString();
    }
}
