package com.example.cosmiccostai.service;

import com.example.cosmiccostai.dto.ReviewResponse;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
