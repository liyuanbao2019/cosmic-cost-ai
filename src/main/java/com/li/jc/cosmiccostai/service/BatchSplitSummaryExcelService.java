package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.BatchSplitSummaryItemResponse;
import com.li.jc.cosmiccostai.dto.BatchSplitSummaryResponse;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class BatchSplitSummaryExcelService {

    private final Path outputBaseDir;

    public BatchSplitSummaryExcelService(@Value("${cosmic.output.base-dir:output}") String outputBaseDir) {
        this.outputBaseDir = Paths.get(outputBaseDir).resolve("batch-summary");
    }

    public Path export(BatchSplitSummaryResponse summary) {
        if (summary == null || !StringUtils.hasText(summary.getBatchId())) {
            throw new BusinessException("批量拆分摘要不存在，无法下载 Excel。");
        }
        Path directory = outputBaseDir.resolve(summary.getBatchId());
        String prefix = StringUtils.hasText(summary.getProjectGroupId())
                ? summary.getProjectGroupId() : summary.getBatchId();
        Path target = directory.resolve(safeFileName(prefix) + "-批量拆分摘要.xlsx");
        Path temporary = directory.resolve("summary-" + UUID.randomUUID().toString() + ".tmp");
        try {
            Files.createDirectories(directory);
            try (XSSFWorkbook workbook = createWorkbook(summary);
                 OutputStream output = Files.newOutputStream(temporary)) {
                workbook.write(output);
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Keep the original export failure.
            }
            throw new BusinessException("生成批量拆分摘要 Excel 失败：" + ex.getMessage(), ex);
        }
    }

    XSSFWorkbook createWorkbook(BatchSplitSummaryResponse summary) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("批量拆分摘要");
        sheet.setDisplayGridlines(false);
        sheet.createFreezePane(0, 10);
        sheet.setAutobreaks(true);
        sheet.getPrintSetup().setLandscape(true);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);

        CellStyle titleStyle = titleStyle(workbook);
        CellStyle subtitleStyle = subtitleStyle(workbook);
        CellStyle metaLabelStyle = metaLabelStyle(workbook);
        CellStyle metaValueStyle = metaValueStyle(workbook);
        CellStyle kpiLabelStyle = kpiLabelStyle(workbook);
        CellStyle kpiNumberStyle = kpiNumberStyle(workbook, "#,##0.00");
        CellStyle kpiIntegerStyle = kpiNumberStyle(workbook, "#,##0");
        CellStyle headerStyle = headerStyle(workbook);
        CellStyle textStyle = bodyStyle(workbook, false, "@");
        CellStyle wrappedTextStyle = bodyStyle(workbook, true, "@");
        CellStyle integerStyle = bodyStyle(workbook, false, "#,##0");
        CellStyle cfpStyle = bodyStyle(workbook, false, "#,##0.00");
        CellStyle successStyle = statusStyle(workbook, IndexedColors.DARK_GREEN, IndexedColors.LIGHT_GREEN);
        CellStyle failureStyle = statusStyle(workbook, IndexedColors.DARK_RED, IndexedColors.ROSE);
        CellStyle totalLabelStyle = totalStyle(workbook, "@");
        CellStyle totalIntegerStyle = totalStyle(workbook, "#,##0");
        CellStyle totalCfpStyle = totalStyle(workbook, "#,##0.00");

        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 7));
        setText(sheet.createRow(0), 0, "COSMIC 批量拆分摘要", titleStyle);
        sheet.getRow(0).setHeightInPoints(34F);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 7));
        setText(sheet.createRow(1), 0, "汇总数据来自各需求最新保存的最终拆分表", subtitleStyle);
        sheet.getRow(1).setHeightInPoints(24F);

        Row meta1 = sheet.createRow(3);
        setText(meta1, 0, "批次编号", metaLabelStyle);
        setText(meta1, 1, summary.getBatchId(), metaValueStyle);
        setText(meta1, 2, "拆分级别", metaLabelStyle);
        setText(meta1, 3, "PROJECT".equals(summary.getSplitLevel()) ? "项目级" : "需求级", metaValueStyle);
        setText(meta1, 4, "项目级编号", metaLabelStyle);
        setText(meta1, 5, value(summary.getProjectGroupId()), metaValueStyle);
        setText(meta1, 6, "项目名称", metaLabelStyle);
        setText(meta1, 7, value(summary.getProjectGroupName()), metaValueStyle);

        Row meta2 = sheet.createRow(4);
        setText(meta2, 0, "需求总数", metaLabelStyle);
        setNumber(meta2, 1, summary.getTotalCount(), metaValueStyle);
        setText(meta2, 2, "统计可用", metaLabelStyle);
        setNumber(meta2, 3, summary.getStatisticsAvailableCount(), metaValueStyle);
        setText(meta2, 4, "项目材料状态", metaLabelStyle);
        setText(meta2, 5, value(summary.getProjectMaterialStatus()), metaValueStyle);
        setText(meta2, 6, "统计说明", metaLabelStyle);
        setText(meta2, 7, summary.getFailedCount() > 0
                ? summary.getFailedCount() + " 个失败需求未计入 CFP" : "全部完成需求已纳入汇总", metaValueStyle);

        Row kpiLabels = sheet.createRow(6);
        sheet.addMergedRegion(new CellRangeAddress(6, 6, 0, 1));
        sheet.addMergedRegion(new CellRangeAddress(6, 6, 2, 3));
        sheet.addMergedRegion(new CellRangeAddress(6, 6, 4, 5));
        sheet.addMergedRegion(new CellRangeAddress(6, 6, 6, 7));
        setText(kpiLabels, 0, "汇总 CFP", kpiLabelStyle);
        setText(kpiLabels, 2, "已完成需求", kpiLabelStyle);
        setText(kpiLabels, 4, "功能过程", kpiLabelStyle);
        setText(kpiLabels, 6, "拆分行数", kpiLabelStyle);
        Row kpiValues = sheet.createRow(7);
        sheet.addMergedRegion(new CellRangeAddress(7, 7, 0, 1));
        sheet.addMergedRegion(new CellRangeAddress(7, 7, 2, 3));
        sheet.addMergedRegion(new CellRangeAddress(7, 7, 4, 5));
        sheet.addMergedRegion(new CellRangeAddress(7, 7, 6, 7));

        int firstDataRow = 10;
        int itemCount = summary.getItems() == null ? 0 : summary.getItems().size();
        int lastDataRow = firstDataRow + Math.max(0, itemCount - 1);
        String dataRange = itemCount == 0 ? null : String.valueOf(firstDataRow + 1) + ":" + (lastDataRow + 1);
        if (dataRange == null) {
            setNumber(kpiValues, 0, 0D, kpiNumberStyle);
            setNumber(kpiValues, 2, 0D, kpiIntegerStyle);
            setNumber(kpiValues, 4, 0D, kpiIntegerStyle);
            setNumber(kpiValues, 6, 0D, kpiIntegerStyle);
        } else {
            setFormula(kpiValues, 0, "SUM(G" + dataRange.replace(":", ":G") + ")", kpiNumberStyle);
            setFormula(kpiValues, 2, "COUNTIF(D" + dataRange.replace(":", ":D")
                    + ",\"已完成\")+COUNTIF(D" + dataRange.replace(":", ":D")
                    + ",\"已完成（统计不可用）\")", kpiIntegerStyle);
            setFormula(kpiValues, 4, "SUM(E" + dataRange.replace(":", ":E") + ")", kpiIntegerStyle);
            setFormula(kpiValues, 6, "SUM(F" + dataRange.replace(":", ":F") + ")", kpiIntegerStyle);
        }
        if ("PROJECT".equals(summary.getSplitLevel())) {
            Row projectKpi = sheet.createRow(8);
            setText(projectKpi, 0, "项目材料 CFP", metaLabelStyle);
            setNumber(projectKpi, 1, summary.getProjectMaterialTotalCfp(), cfpStyle);
            setText(projectKpi, 2, "失败/中断需求", metaLabelStyle);
            setNumber(projectKpi, 3, summary.getFailedCount(), integerStyle);
        }

        String[] headers = {"序号", "需求文档", "项目编号", "状态", "功能过程", "拆分行", "CFP", "说明"};
        Row header = sheet.createRow(9);
        for (int column = 0; column < headers.length; column++) {
            setText(header, column, headers[column], headerStyle);
        }
        header.setHeightInPoints(25F);

        int rowIndex = firstDataRow;
        if (summary.getItems() != null) {
            for (int index = 0; index < summary.getItems().size(); index++) {
                BatchSplitSummaryItemResponse item = summary.getItems().get(index);
                Row row = sheet.createRow(rowIndex++);
                setNumber(row, 0, index + 1, integerStyle);
                setText(row, 1, firstText(item.getSourceFileName(), item.getProjectName(), item.getProjectId()), textStyle);
                setText(row, 2, value(item.getProjectId()), textStyle);
                String status = displayStatus(item);
                setText(row, 3, status, item.isStatisticsAvailable() ? successStyle : failureStyle);
                if (item.isStatisticsAvailable()) {
                    setNumber(row, 4, item.getFunctionProcessCount(), integerStyle);
                    setNumber(row, 5, item.getMovementRowCount(), integerStyle);
                    setNumber(row, 6, item.getTotalCfp(), cfpStyle);
                } else {
                    setText(row, 4, "-", integerStyle);
                    setText(row, 5, "-", integerStyle);
                    setText(row, 6, "-", cfpStyle);
                }
                setText(row, 7, value(item.getMessage()), wrappedTextStyle);
                row.setHeightInPoints(26F);
            }
        }

        Row total = sheet.createRow(rowIndex);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 3));
        setText(total, 0, "合计", totalLabelStyle);
        if (itemCount > 0) {
            setFormula(total, 4, "SUM(E" + (firstDataRow + 1) + ":E" + (lastDataRow + 1) + ")", totalIntegerStyle);
            setFormula(total, 5, "SUM(F" + (firstDataRow + 1) + ":F" + (lastDataRow + 1) + ")", totalIntegerStyle);
            setFormula(total, 6, "SUM(G" + (firstDataRow + 1) + ":G" + (lastDataRow + 1) + ")", totalCfpStyle);
        } else {
            setNumber(total, 4, 0D, totalIntegerStyle);
            setNumber(total, 5, 0D, totalIntegerStyle);
            setNumber(total, 6, 0D, totalCfpStyle);
        }
        setText(total, 7, "仅统计可读取最终拆分表的已完成需求", totalLabelStyle);
        total.setHeightInPoints(26F);

        if (itemCount > 0) {
            sheet.setAutoFilter(new CellRangeAddress(9, lastDataRow, 0, 7));
        }
        int[] widths = {7, 44, 27, 20, 12, 12, 14, 52};
        for (int column = 0; column < widths.length; column++) {
            sheet.setColumnWidth(column, widths[column] * 256);
        }
        sheet.setRepeatingRows(new CellRangeAddress(9, 9, -1, -1));
        workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
        return workbook;
    }

    private CellStyle titleStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        Font font = workbook.createFont();
        font.setFontName("微软雅黑");
        font.setFontHeightInPoints((short) 18);
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private CellStyle subtitleStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        Font font = workbook.createFont();
        font.setFontName("微软雅黑");
        font.setFontHeightInPoints((short) 10);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private CellStyle metaLabelStyle(XSSFWorkbook workbook) {
        CellStyle style = baseStyle(workbook);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        workbook.getFontAt(style.getFontIndex()).setBold(true);
        return style;
    }

    private CellStyle metaValueStyle(XSSFWorkbook workbook) {
        CellStyle style = baseStyle(workbook);
        style.setWrapText(true);
        return style;
    }

    private CellStyle kpiLabelStyle(XSSFWorkbook workbook) {
        CellStyle style = baseStyle(workbook);
        style.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        workbook.getFontAt(style.getFontIndex()).setBold(true);
        return style;
    }

    private CellStyle kpiNumberStyle(XSSFWorkbook workbook, String numberFormat) {
        CellStyle style = baseStyle(workbook);
        style.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setDataFormat(workbook.createDataFormat().getFormat(numberFormat));
        Font font = workbook.createFont();
        font.setFontName("微软雅黑");
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        font.setColor(IndexedColors.DARK_TEAL.getIndex());
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = baseStyle(workbook);
        style.setFillForegroundColor(IndexedColors.TEAL.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        Font font = workbook.createFont();
        font.setFontName("微软雅黑");
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private CellStyle bodyStyle(XSSFWorkbook workbook, boolean wrap, String numberFormat) {
        CellStyle style = baseStyle(workbook);
        style.setWrapText(wrap);
        style.setDataFormat(workbook.createDataFormat().getFormat(numberFormat));
        return style;
    }

    private CellStyle statusStyle(XSSFWorkbook workbook, IndexedColors fontColor, IndexedColors fillColor) {
        CellStyle style = baseStyle(workbook);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor(fillColor.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        workbook.getFontAt(style.getFontIndex()).setBold(true);
        workbook.getFontAt(style.getFontIndex()).setColor(fontColor.getIndex());
        return style;
    }

    private CellStyle totalStyle(XSSFWorkbook workbook, String numberFormat) {
        CellStyle style = baseStyle(workbook);
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setDataFormat(workbook.createDataFormat().getFormat(numberFormat));
        workbook.getFontAt(style.getFontIndex()).setBold(true);
        return style;
    }

    private CellStyle baseStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        Font font = workbook.createFont();
        font.setFontName("微软雅黑");
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        return style;
    }

    private void setText(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private void setNumber(Row row, int column, double value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private void setFormula(Row row, int column, String formula, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellFormula(formula);
        cell.setCellStyle(style);
    }

    private String displayStatus(BatchSplitSummaryItemResponse item) {
        if ("COMPLETED".equals(item.getStatus())) {
            return item.isStatisticsAvailable() ? "已完成" : "已完成（统计不可用）";
        }
        if ("FAILED".equals(item.getStatus())) return "失败/中断";
        return value(item.getStatus());
    }

    private String firstText(String... values) {
        if (values != null) {
            for (String item : values) {
                if (StringUtils.hasText(item)) return item.trim();
            }
        }
        return "-";
    }

    private String value(String text) {
        return StringUtils.hasText(text) ? text.trim() : "-";
    }

    private String safeFileName(String value) {
        String safe = value == null ? "batch" : value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "batch" : safe;
    }
}
