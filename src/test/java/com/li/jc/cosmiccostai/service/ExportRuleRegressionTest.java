package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.ExcelExportResponse;
import com.li.jc.cosmiccostai.dto.WordExportResponse;
import com.li.jc.cosmiccostai.model.CosmicResult;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportRuleRegressionTest {

    @Test
    void exportsFixedSequenceRulesAndWordSections(@TempDir Path tempDir) throws Exception {
        FileStorageService storageService = new FileStorageService(tempDir.resolve("storage").toString());
        prepareProject(storageService, "CODEX_RULE_TEST");
        String outputDir = tempDir.resolve("output").toString();
        ExcelExportService excelExportService = new ExcelExportService(storageService, outputDir,
                "templates/附件3-XX项目功能点拆分表V1.0.xlsx");
        WordExportService wordExportService = new WordExportService(storageService, outputDir,
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
            assertNull(sheet.getRow(2).getCell(13));
            assertNull(sheet.getRow(2).getCell(14));
            assertNull(sheet.getRow(5).getCell(13));
            assertNull(sheet.getRow(5).getCell(14));
            assertTrue(sheet.isColumnHidden(13));
            assertTrue(sheet.isColumnHidden(14));
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
        assertTrue(text.contains("rule-test"));
        assertFalse(text.contains("XX项目需求说明书"));
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

    private void prepareProject(FileStorageService storageService, String projectId) throws Exception {
        Path projectDir = storageService.resolveProjectDir(projectId);
        Files.createDirectories(projectDir);
        Path resultsPath = projectDir.resolve("cosmic-results.json");
        Path rowsPath = projectDir.resolve("function-split-rows.json");

        CosmicResult result = new CosmicResult();
        result.setModule("公告管理");
        result.setFunction("新增公告信息");
        result.setDescription("运营人员填写公告标题并发布公告信息。");
        result.setFunctionalUser("运营人员");
        result.setTriggerEvent("运营人员提交公告信息");
        result.setSequenceDiagram("UI->UI;后台服务->后台服务;后台服务->UI");
        result.setEntry(1);
        result.setWrite(1);
        result.setExit(1);
        result.setCfp(3D);

        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        rows.add(row("E", "录入公告信息", "公告录入数据", "标题、内容、发布范围、生效时间", "UI->UI"));
        rows.add(row("W", "保存公告信息", "公告信息表", "标题、内容、发布范围、生效时间", "后台服务->后台服务"));
        rows.add(row("X", "返回公告发布结果", "公告发布结果", "发布状态、结果说明", "后台服务->UI"));

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.writeValue(resultsPath.toFile(), java.util.Collections.singletonList(result));
        objectMapper.writeValue(rowsPath.toFile(), rows);

        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(projectId);
        metadata.setName("rule-test");
        metadata.setSourceFileName("rule-test.docx");
        metadata.setFileType("docx");
        metadata.setStatus("REVIEWED");
        metadata.setCosmicResultsPath(resultsPath.toString());
        metadata.setFunctionSplitRowsPath(rowsPath.toString());
        metadata.setCreatedAt("2026-08-08 00:00:00");
        metadata.setUpdatedAt("2026-08-08 00:00:00");
        storageService.saveMetadata(metadata);
    }

    private FunctionSplitRow row(String movement, String subProcess, String dataGroup,
                                 String attributes, String sequence) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setProjectName("rule-test");
        row.setLevel1Module("公告管理");
        row.setLevel2Module("公告管理");
        row.setLevel3Module("公告发布");
        row.setFunctionalUser("运营人员");
        row.setTriggerEvent("运营人员提交公告信息");
        row.setFunctionProcess("新增公告信息");
        row.setFunctionDescription("运营人员填写公告标题并发布公告信息。");
        row.setSubProcessDescription(subProcess);
        row.setMovementType(movement);
        row.setDataGroup(dataGroup);
        row.setDataAttributes(attributes);
        row.setSequenceDiagram(sequence);
        row.setReuseDegree("新增");
        row.setCfp(1D);
        return row;
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
