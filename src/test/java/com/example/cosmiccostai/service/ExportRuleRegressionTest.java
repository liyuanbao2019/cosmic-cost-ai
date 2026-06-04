package com.example.cosmiccostai.service;

import com.example.cosmiccostai.dto.ExcelExportResponse;
import com.example.cosmiccostai.dto.WordExportResponse;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportRuleRegressionTest {

    @Test
    void exportsFixedSequenceRulesAndWordSections() throws Exception {
        FileStorageService storageService = new FileStorageService("storage");
        ExcelExportService excelExportService = new ExcelExportService(storageService, "output",
                "templates/附件3-XX项目功能点拆分表V1.0.xlsx");
        WordExportService wordExportService = new WordExportService(storageService, "output",
                "templates/附件1-XX项目需求说明书V1.0.docx");

        ExcelExportResponse excel = excelExportService.export("CODEX_RULE_TEST");
        WordExportResponse word = wordExportService.export("CODEX_RULE_TEST");

        Path excelPath = Paths.get(excel.getExcelOutputPath());
        Path wordPath = Paths.get(word.getWordOutputPath());
        assertTrue(Files.exists(excelPath));
        assertTrue(Files.exists(wordPath));

        try (InputStream inputStream = Files.newInputStream(excelPath);
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheet("2、功能点拆分表");
            if (sheet == null) {
                sheet = workbook.getSheetAt(2);
            }
            assertEquals("功能描述", sheet.getRow(2).getCell(13).getStringCellValue());
            assertEquals("时序图", sheet.getRow(2).getCell(14).getStringCellValue());
            assertEquals("UI->UI", cell(sheet, 5, 14));
            assertEquals("UI->后台服务", cell(sheet, 6, 14));
            assertEquals("后台服务->后台服务", cell(sheet, 7, 14));
            assertEquals("后台服务->UI", cell(sheet, 8, 14));
            assertEquals("后台服务->UI", cell(sheet, 9, 14));
            assertTrue(cell(sheet, 5, 13).contains("录入公告信息"));
            assertTrue(cell(sheet, 5, 13).contains("标题、内容、发布范围、生效时间"));
            assertFalse(cell(sheet, 5, 13).equals("录入公告信息"));
        }

        String documentXml;
        int mediaCount = 0;
        try (ZipFile zipFile = new ZipFile(wordPath.toFile())) {
            ZipEntry documentEntry = zipFile.getEntry("word/document.xml");
            documentXml = readAll(zipFile.getInputStream(documentEntry));
            for (ZipEntry entry : zipEntries(zipFile)) {
                if (entry.getName().startsWith("word/media/")) {
                    mediaCount++;
                }
            }
        }
        String text = documentXml.replaceAll("<[^>]+>", "");
        assertTrue(mediaCount > 0);
        assertTrue(text.startsWith("rule-test"));
        assertFalse(text.startsWith("XX项目需求说明书"));
        YearMonth now = YearMonth.now();
        assertTrue(text.contains(now.getYear() + "年" + now.getMonthValue() + "月"));
        assertFalse(text.contains("XXXX年XX月"));
        assertTrue(text.contains("关键时序图/业务逻辑图"));
        assertTrue(text.contains("本时序图步骤如下："));
        assertTrue(text.contains("功能描述"));
        assertTrue(text.contains("1) 录入公告信息（标题、内容、发布范围、生效时间）"));
        assertFalse(text.contains("1) E "));
        assertEquals("10", paragraphStyle(documentXml, "关键时序图/业务逻辑图"));
        assertEquals("10", paragraphStyle(documentXml, "本时序图步骤如下："));
        assertEquals("10", paragraphStyle(documentXml, "功能描述"));
        assertEquals("2", paragraphStyle(documentXml, "1) 录入公告信息"));
        assertEquals("2", paragraphStyle(documentXml, "运营人员填写公告标题"));
        assertTrue(count(documentXml, "UI-&gt;UI") <= 1);
    }

    private String cell(Sheet sheet, int rowIndex, int colIndex) {
        Row row = sheet.getRow(rowIndex);
        return row.getCell(colIndex).getStringCellValue();
    }

    private List<ZipEntry> zipEntries(ZipFile zipFile) {
        List<ZipEntry> entries = new ArrayList<ZipEntry>();
        Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
        while (enumeration.hasMoreElements()) {
            entries.add(enumeration.nextElement());
        }
        return entries;
    }

    private String readAll(InputStream inputStream) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        while ((read = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private int count(String text, String pattern) {
        Matcher matcher = Pattern.compile(Pattern.quote(pattern)).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private String paragraphStyle(String documentXml, String text) {
        Pattern paragraphPattern = Pattern.compile("<w:p[\\s\\S]*?</w:p>");
        Matcher paragraphMatcher = paragraphPattern.matcher(documentXml);
        while (paragraphMatcher.find()) {
            String paragraph = paragraphMatcher.group();
            if (!paragraph.contains(text)) {
                continue;
            }
            Matcher styleMatcher = Pattern.compile("<w:pStyle[^>]*w:val=\"([^\"]+)\"").matcher(paragraph);
            return styleMatcher.find() ? styleMatcher.group(1) : "";
        }
        return "";
    }
}
