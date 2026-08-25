package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ProjectAggregateExportServiceTest {

    @Test
    void writesOneProjectRequirementSpecificationSummaryAndSplitWorkbook() throws Exception {
        FileStorageService storage = mock(FileStorageService.class);
        RuleValidationService validation = new RuleValidationService();
        Path outputDir = Paths.get("target", "project-material-qa");
        Files.createDirectories(outputDir);
        List<FunctionSplitRow> rows = Arrays.asList(
                row("E", "接收客户资料查询请求", "客户资料查询请求", "客户编号、查询条件", "业务人员->本系统"),
                row("R", "读取客户资料", "客户资料", "客户编号、客户名称、客户状态", "本系统->本系统"),
                row("X", "返回客户资料查询结果", "客户资料查询结果", "客户编号、客户名称、客户状态", "本系统->业务人员"));

        ExcelExportService excel = new ExcelExportService(storage, "target/project-material-qa", "",
                mock(FunctionDescriptionAiService.class), validation);
        ExcelExportService.ProjectExcelExportResult excelResult = excel.exportProjectMaterial(
                "BSQA", "新疆移动示例项目", rows, outputDir);
        assertEquals(1, excelResult.getRequirementCount());
        assertEquals(3D, excelResult.getTotalCfp());
        try (InputStream input = Files.newInputStream(excelResult.getAttachment2Path());
             Workbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheet("功能需求列表");
            DataFormatter formatter = new DataFormatter();
            assertEquals("新疆移动示例项目", formatter.formatCellValue(sheet.getRow(3).getCell(1)));
            assertEquals("3", formatter.formatCellValue(sheet.getRow(3).getCell(4)));
        }
        try (InputStream input = Files.newInputStream(excelResult.getAttachment3Path());
             Workbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheet("2、功能点拆分表");
            DataFormatter formatter = new DataFormatter();
            assertEquals("需求甲", formatter.formatCellValue(sheet.getRow(5).getCell(1)));
            assertEquals("客户资料查询", formatter.formatCellValue(sheet.getRow(5).getCell(6)));
        }

        FunctionDescriptionAiService descriptionAi = new FunctionDescriptionAiService() {
            @Override
            public Map<String, Map<String, String>> generateDescriptionsByRequirementConcurrently(
                    Map<String, Map<String, List<FunctionSplitRow>>> requirements,
                    RequirementProgressCallback progressCallback) {
                Map<String, Map<String, String>> result =
                        new LinkedHashMap<String, Map<String, String>>();
                int groups = 0;
                for (Map<String, List<FunctionSplitRow>> value : requirements.values()) groups += value.size();
                if (progressCallback != null) progressCallback.onProgress(0, requirements.size(), 0, groups, requirements.size());
                for (Map.Entry<String, Map<String, List<FunctionSplitRow>>> requirement : requirements.entrySet()) {
                    Map<String, String> descriptions = new LinkedHashMap<String, String>();
                    for (String key : requirement.getValue().keySet()) {
                        descriptions.put(key, "大模型生成的客户资料查询功能描述。");
                    }
                    result.put(requirement.getKey(), descriptions);
                }
                if (progressCallback != null) progressCallback.onProgress(requirements.size(), requirements.size(), groups, groups, 0);
                return result;
            }
        };
        WordExportService word = new WordExportService(storage, "target/project-material-qa", "",
                descriptionAi, mock(ExportProgressService.class), validation);
        WordExportService.ProjectWordExportResult wordResult = word.exportProjectMaterial(
                "BSQA", "新疆移动示例项目", rows, outputDir);
        assertEquals(1, wordResult.getFunctionCount());
        assertEquals(3D, wordResult.getTotalCfp());
        try (InputStream input = Files.newInputStream(wordResult.getAttachment1Path());
             XWPFDocument document = new XWPFDocument(input)) {
            assertTrue(document.getParagraphs().stream().map(XWPFParagraph::getText)
                    .anyMatch(text -> text != null && text.contains("需求甲")));
            assertTrue(document.getParagraphs().stream().map(XWPFParagraph::getText)
                    .anyMatch(text -> text != null && text.contains("客户资料查询")));
            assertTrue(document.getParagraphs().stream().map(XWPFParagraph::getText)
                    .anyMatch(text -> text != null && text.contains("大模型生成的客户资料查询功能描述")));
        }
    }

    private FunctionSplitRow row(String movement, String subprocess, String dataGroup,
                                 String attributes, String sequence) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setProjectName("新疆移动示例项目");
        row.setLevel1Module("需求甲");
        row.setLevel2Module("客户管理");
        row.setLevel3Module("客户资料");
        row.setFunctionalUser("业务人员");
        row.setTriggerEvent("业务人员发起客户资料查询");
        row.setFunctionProcess("客户资料查询");
        row.setFunctionDescription("根据查询条件读取客户资料并返回查询结果。");
        row.setSubProcessDescription(subprocess);
        row.setMovementType(movement);
        row.setDataGroup(dataGroup);
        row.setDataAttributes(attributes);
        row.setSequenceDiagram(sequence);
        row.setReuseDegree("新增");
        row.setCfp(1D);
        return row;
    }
}
