package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.ReviewResponse;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelReviewImportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void importsSplitExcelIntoReviewRows() throws Exception {
        FileStorageService fileStorageService = new FileStorageService(tempDir.toString());
        ReviewService reviewService = new ReviewService(fileStorageService);
        ExcelReviewImportService importService = new ExcelReviewImportService(fileStorageService, reviewService);

        MockMultipartFile file = new MockMultipartFile("file", "split.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookBytes());

        ReviewResponse response = importService.importSplitExcel(file, "review-import-project");

        assertEquals("EXCEL_IMPORTED", response.getStatus());
        assertEquals(2, response.getSplitRows().size());
        assertEquals(1, response.getFunctionCount());
        assertEquals(4.0 / 3.0, response.getTotalCfp(), 0.000001);
        assertEquals("\u65b0\u589e", response.getSplitRows().get(0).getReuseDegree());
        assertEquals("process-a", response.getSplitRows().get(1).getFunctionProcess());
        assertEquals("module-a", response.getSplitRows().get(1).getLevel1Module());
        assertTrue(Files.exists(tempDir.resolve("projects").resolve(response.getProjectId()).resolve("cosmic-results.json")));
        assertTrue(Files.exists(tempDir.resolve("projects").resolve(response.getProjectId()).resolve("function-split-rows.json")));
    }

    @Test
    void doesNotPolishFunctionDescriptionByDefault() throws Exception {
        FileStorageService fileStorageService = new FileStorageService(tempDir.toString());
        TrackingFunctionDescriptionAiService aiService = new TrackingFunctionDescriptionAiService();
        ReviewService reviewService = new ReviewService(fileStorageService, aiService);
        ExcelReviewImportService importService = new ExcelReviewImportService(fileStorageService, reviewService);

        MockMultipartFile file = new MockMultipartFile("file", "split.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookBytes());

        ReviewResponse response = importService.importSplitExcel(file, "review-import-project");

        assertEquals(0, aiService.callCount);
        assertEquals("process-a description", response.getSplitRows().get(0).getFunctionDescription());
    }

    @Test
    void ignoresFunctionDescriptionPolishRequestDuringImport() throws Exception {
        FileStorageService fileStorageService = new FileStorageService(tempDir.toString());
        TrackingFunctionDescriptionAiService aiService = new TrackingFunctionDescriptionAiService();
        ReviewService reviewService = new ReviewService(fileStorageService, aiService);
        ExcelReviewImportService importService = new ExcelReviewImportService(fileStorageService, reviewService);

        MockMultipartFile file = new MockMultipartFile("file", "split.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookBytes());

        importService.importSplitExcel(file, "review-import-project", true);

        assertEquals(0, aiService.callCount);
    }

    @Test
    void ignoresTrailingTemplateInstructionRow() throws Exception {
        FileStorageService fileStorageService = new FileStorageService(tempDir.toString());
        ReviewService reviewService = new ReviewService(fileStorageService);
        ExcelReviewImportService importService = new ExcelReviewImportService(fileStorageService, reviewService);

        MockMultipartFile file = new MockMultipartFile("file", "split-with-note.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                workbookBytesWithInstructionRow());

        ReviewResponse response = importService.importSplitExcel(file, "review-import-project");

        assertEquals("EXCEL_IMPORTED", response.getStatus());
        assertEquals(2, response.getSplitRows().size());
    }

    @Test
    void ignoresMergedBlankTailRowsWithHiddenFormattingArtifacts() throws Exception {
        FileStorageService fileStorageService = new FileStorageService(tempDir.toString());
        ReviewService reviewService = new ReviewService(fileStorageService);
        ExcelReviewImportService importService = new ExcelReviewImportService(fileStorageService, reviewService);
        MockMultipartFile file = new MockMultipartFile("file", "split-with-merged-blank-tail.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                workbookBytesWithMergedBlankTail());

        int rowCount = importService.validateSplitExcel(file, "review-import-project");

        assertEquals(2, rowCount);
    }

    @Test
    void ignoresTotalRowWithCfpValueAndNote() throws Exception {
        FileStorageService fileStorageService = new FileStorageService(tempDir.toString());
        ReviewService reviewService = new ReviewService(fileStorageService);
        ExcelReviewImportService importService = new ExcelReviewImportService(fileStorageService, reviewService);
        MockMultipartFile file = new MockMultipartFile("file", "split-with-total.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                workbookBytesWithTotalRow());

        int rowCount = importService.validateSplitExcel(file, "review-import-project");

        assertEquals(2, rowCount);
    }

    @Test
    void prevalidationDoesNotCreateAProjectOrPersistTheWorkbook() throws Exception {
        FileStorageService fileStorageService = new FileStorageService(tempDir.toString());
        ReviewService reviewService = new ReviewService(fileStorageService);
        ExcelReviewImportService importService = new ExcelReviewImportService(fileStorageService, reviewService);
        MockMultipartFile file = new MockMultipartFile("file", "split.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookBytes());

        int rowCount = importService.validateSplitExcel(file, "review-import-project");

        assertEquals(2, rowCount);
        Path projects = tempDir.resolve("projects");
        if (Files.exists(projects)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(projects)) {
                assertFalse(entries.iterator().hasNext());
            }
        }
    }

    private byte[] workbookBytes() throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("developer-split");
            Row header = sheet.createRow(2);
            header.createCell(0).setCellValue("\u5ba2\u6237\u9700\u6c42");
            header.createCell(1).setCellValue("\u4e00\u7ea7\u6a21\u5757");
            header.createCell(6).setCellValue("\u529f\u80fd\u8fc7\u7a0b");
            header.createCell(8).setCellValue("\u6570\u636e\u79fb\u52a8");
            header.createCell(9).setCellValue("\u6570\u636e\u7ec4");
            header.createCell(11).setCellValue("\u590d\u7528\u5ea6");
            Row first = sheet.createRow(4);
            first.createCell(0).setCellValue("review-import-project");
            first.createCell(1).setCellValue("module-a");
            first.createCell(2).setCellValue("module-a-2");
            first.createCell(3).setCellValue("module-a-3");
            first.createCell(4).setCellValue("user-a");
            first.createCell(5).setCellValue("trigger-a");
            first.createCell(6).setCellValue("process-a");
            first.createCell(7).setCellValue("receive process-a request");
            first.createCell(8).setCellValue("E");
            first.createCell(9).setCellValue("request");
            first.createCell(10).setCellValue("id,status,time");
            first.createCell(13).setCellValue("process-a description");
            first.createCell(14).setCellValue("UI->UI");

            Row second = sheet.createRow(5);
            second.createCell(7).setCellValue("read process-a data");
            second.createCell(8).setCellValue("R");
            second.createCell(9).setCellValue("record");
            second.createCell(10).setCellValue("id,state");
            second.createCell(11).setCellValue("\u590d\u7528");

            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private byte[] workbookBytesWithInstructionRow() throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("2、功能点拆分表");
            Row header = sheet.createRow(2);
            header.createCell(0).setCellValue("客户需求");
            header.createCell(1).setCellValue("一级模块");
            header.createCell(6).setCellValue("功能过程");
            header.createCell(8).setCellValue("数据移动");
            header.createCell(9).setCellValue("数据组");
            header.createCell(11).setCellValue("复用度");
            Row first = sheet.createRow(4);
            first.createCell(0).setCellValue("review-import-project");
            first.createCell(1).setCellValue("module-a");
            first.createCell(6).setCellValue("process-a");
            first.createCell(7).setCellValue("receive request");
            first.createCell(8).setCellValue("E");
            first.createCell(9).setCellValue("request");
            first.createCell(10).setCellValue("id,status");
            Row second = sheet.createRow(5);
            second.createCell(7).setCellValue("write request");
            second.createCell(8).setCellValue("W");
            second.createCell(9).setCellValue("request record");
            second.createCell(10).setCellValue("id,status,time");
            Row note = sheet.createRow(6);
            note.createCell(0).setCellValue("注意：A~M列之间不允许增加新列，说明文字提交时删除。");
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private byte[] workbookBytesWithMergedBlankTail() throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("2、功能点拆分表");
            Row header = sheet.createRow(2);
            header.createCell(0).setCellValue("客户需求");
            header.createCell(1).setCellValue("一级模块");
            header.createCell(6).setCellValue("功能过程");
            header.createCell(8).setCellValue("数据移动");
            header.createCell(9).setCellValue("数据组");
            Row first = sheet.createRow(4);
            first.createCell(0).setCellValue("review-import-project");
            first.createCell(1).setCellValue("module-a");
            first.createCell(6).setCellValue("process-a");
            first.createCell(7).setCellValue("receive request");
            first.createCell(8).setCellValue("E");
            first.createCell(9).setCellValue("request");
            first.createCell(10).setCellValue("id,status");
            Row second = sheet.createRow(5);
            second.createCell(7).setCellValue("write request");
            second.createCell(8).setCellValue("W");
            second.createCell(9).setCellValue("request record");
            second.createCell(10).setCellValue("id,status,time");

            CellStyle blankStyle = workbook.createCellStyle();
            blankStyle.setWrapText(true);
            Row mergedTop = sheet.createRow(2241);
            Row mergedBottom = sheet.createRow(2242);
            for (int columnIndex = 0; columnIndex <= 14; columnIndex++) {
                mergedTop.createCell(columnIndex).setCellStyle(blankStyle);
                mergedBottom.createCell(columnIndex).setCellStyle(blankStyle);
            }
            mergedTop.getCell(0).setCellValue("\u200B");
            mergedTop.getCell(13).setCellValue("自动生成占位符");
            sheet.setColumnHidden(13, true);
            sheet.addMergedRegion(new CellRangeAddress(2241, 2242, 0, 12));

            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private byte[] workbookBytesWithTotalRow() throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("2、功能点拆分表");
            Row header = sheet.createRow(2);
            header.createCell(0).setCellValue("客户需求");
            header.createCell(1).setCellValue("一级模块");
            header.createCell(6).setCellValue("功能过程");
            header.createCell(8).setCellValue("数据移动");
            header.createCell(9).setCellValue("数据组");
            Row first = sheet.createRow(4);
            first.createCell(0).setCellValue("review-import-project");
            first.createCell(1).setCellValue("module-a");
            first.createCell(6).setCellValue("process-a");
            first.createCell(7).setCellValue("receive request");
            first.createCell(8).setCellValue("E");
            first.createCell(9).setCellValue("request");
            first.createCell(10).setCellValue("id,status");
            Row second = sheet.createRow(5);
            second.createCell(7).setCellValue("write request");
            second.createCell(8).setCellValue("W");
            second.createCell(9).setCellValue("request record");
            second.createCell(10).setCellValue("id,status,time");
            Row total = sheet.createRow(6);
            total.createCell(0).setCellValue("合计");
            total.createCell(12).setCellValue(2186);
            total.createCell(14).setCellValue("合计行相关的不纳入校验");

            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private static class TrackingFunctionDescriptionAiService extends FunctionDescriptionAiService {
        private int callCount;

        @Override
        public Map<String, String> generateDescriptions(Map<String, List<FunctionSplitRow>> groups) {
            callCount++;
            return Collections.emptyMap();
        }
    }
}
