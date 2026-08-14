package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.BatchSplitSummaryItemResponse;
import com.li.jc.cosmiccostai.dto.BatchSplitSummaryResponse;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchSplitSummaryExcelServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void exportsReadableWorkbookWithFormulaTotalsAndFailureDetails() throws Exception {
        BatchSplitSummaryResponse summary = sampleSummary();
        BatchSplitSummaryExcelService service = new BatchSplitSummaryExcelService(tempDir.toString());

        Path output = service.export(summary);

        assertTrue(Files.isRegularFile(output));
        try (InputStream input = Files.newInputStream(output);
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheet("批量拆分摘要");
            assertEquals("COSMIC 批量拆分摘要", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("需求一.docx", sheet.getRow(10).getCell(1).getStringCellValue());
            assertEquals(12D, sheet.getRow(10).getCell(6).getNumericCellValue());
            assertEquals("失败/中断", sheet.getRow(11).getCell(3).getStringCellValue());
            assertEquals(CellType.FORMULA, sheet.getRow(12).getCell(6).getCellType());
            assertEquals("SUM(G11:G12)", sheet.getRow(12).getCell(6).getCellFormula());
            assertEquals(10, sheet.getPaneInformation().getHorizontalSplitPosition());
            assertTrue(sheet.getColumnWidth(1) >= 40 * 256);
            assertEquals(HorizontalAlignment.CENTER,
                    sheet.getRow(6).getCell(0).getCellStyle().getAlignment());
            assertEquals(HorizontalAlignment.CENTER,
                    sheet.getRow(7).getCell(0).getCellStyle().getAlignment());

            Path qaDirectory = Paths.get("target", "batch-summary-qa");
            Files.createDirectories(qaDirectory);
            Path qaOutput = qaDirectory.resolve("batch-summary-sample.xlsx");
            try (OutputStream qa = Files.newOutputStream(qaOutput)) {
                workbook.write(qa);
            }
        }
    }

    private BatchSplitSummaryResponse sampleSummary() {
        BatchSplitSummaryResponse summary = new BatchSplitSummaryResponse();
        summary.setBatchId("BS20260813193000TEST");
        summary.setSplitLevel("PROJECT");
        summary.setProjectGroupId("PJ202608130001");
        summary.setProjectGroupName("家客终端管理项目");
        summary.setTotalCount(2);
        summary.setCompletedCount(1);
        summary.setFailedCount(1);
        summary.setStatisticsAvailableCount(1);
        summary.setFunctionProcessCount(3);
        summary.setMovementRowCount(12);
        summary.setTotalCfp(12D);
        summary.setProjectMaterialStatus("BLOCKED");
        summary.setProjectMaterialTotalCfp(0D);

        BatchSplitSummaryItemResponse completed = new BatchSplitSummaryItemResponse();
        completed.setProjectId("P202608130001");
        completed.setProjectName("需求一");
        completed.setSourceFileName("需求一.docx");
        completed.setStatus("COMPLETED");
        completed.setStatisticsAvailable(true);
        completed.setFunctionProcessCount(3);
        completed.setMovementRowCount(12);
        completed.setTotalCfp(12D);

        BatchSplitSummaryItemResponse failed = new BatchSplitSummaryItemResponse();
        failed.setProjectId("P202608130002");
        failed.setProjectName("需求二");
        failed.setSourceFileName("需求二.docx");
        failed.setStatus("FAILED");
        failed.setStatisticsAvailable(false);
        failed.setMessage("AI 输出达到上限，JSON 解析失败；该需求未计入 CFP 汇总。");
        summary.setItems(Arrays.asList(completed, failed));
        return summary;
    }
}
