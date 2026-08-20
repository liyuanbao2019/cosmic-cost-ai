package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectMaterialExportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesOneBatchZipContainingEachRequirementArchive() throws Exception {
        FileStorageService storage = mock(FileStorageService.class);
        ExcelExportService excel = mock(ExcelExportService.class);
        WordExportService word = mock(WordExportService.class);
        Path projectOutput = tempDir.resolve("single-outputs");
        Files.createDirectories(projectOutput);
        ProjectMetadata first = new ProjectMetadata();
        first.setId("P1");
        first.setSourceFileName("1.需求甲.docx");
        ProjectMetadata second = new ProjectMetadata();
        second.setId("P2");
        second.setSourceFileName("2.需求乙.docx");
        when(storage.loadMetadata("P1")).thenReturn(first);
        when(storage.loadMetadata("P2")).thenReturn(second);
        when(excel.export(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            ProjectMetadata metadata = "P1".equals(id) ? first : second;
            metadata.setExcelOutputPath(Files.write(projectOutput.resolve(id + "-附件3.xlsx"), id.getBytes()).toString());
            metadata.setBusinessRequirementOutputPath(Files.write(projectOutput.resolve(id + "-附件2.xlsx"), id.getBytes()).toString());
            return null;
        });
        when(word.export(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            ProjectMetadata metadata = "P1".equals(id) ? first : second;
            metadata.setWordOutputPath(Files.write(projectOutput.resolve(id + "-附件1.docx"), id.getBytes()).toString());
            return null;
        });
        ProjectMaterialExportService service = new ProjectMaterialExportService(
                storage, excel, word, tempDir.toString());

        ProjectMaterialExportService.RequirementBatchArchive result =
                service.exportRequirementArchives("BS001", Arrays.asList("P1", "P2"));

        assertEquals(2, result.getSuccessCount());
        assertTrue(result.getFailedProjects().isEmpty());
        assertTrue(Files.isRegularFile(result.getArchivePath()));
        Set<String> entries = new HashSet<String>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(result.getArchivePath()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.add(entry.getName());
        }
        assertEquals(new HashSet<String>(Arrays.asList(
                "1.需求甲-送审材料.zip", "2.需求乙-送审材料.zip")), entries);
    }

    @Test
    void deletesAggregateVersionsAndIndividualRequirementOutputs() throws Exception {
        ProjectMaterialExportService service = new ProjectMaterialExportService(
                mock(FileStorageService.class), mock(ExcelExportService.class),
                mock(WordExportService.class), tempDir.toString());
        Path aggregateRoot = tempDir.resolve("project-materials");
        Files.createDirectories(aggregateRoot.resolve("PJABC-V1"));
        Files.createDirectories(aggregateRoot.resolve("PJABC-V2"));
        Files.createDirectories(aggregateRoot.resolve("BSXYZ"));
        Files.createDirectories(aggregateRoot.resolve("PJRETAIN-V1"));
        Files.createDirectories(tempDir.resolve("P111"));
        Files.createDirectories(tempDir.resolve("P222"));

        assertEquals(3, service.deleteMaterialVersions("PJABC", "BSXYZ"));
        assertEquals(2, service.deleteRequirementOutputs(Arrays.asList("P111", "P222")));

        assertFalse(Files.exists(aggregateRoot.resolve("PJABC-V1")));
        assertFalse(Files.exists(aggregateRoot.resolve("PJABC-V2")));
        assertFalse(Files.exists(aggregateRoot.resolve("BSXYZ")));
        assertTrue(Files.exists(aggregateRoot.resolve("PJRETAIN-V1")));
        assertFalse(Files.exists(tempDir.resolve("P111")));
        assertFalse(Files.exists(tempDir.resolve("P222")));
    }

    @Test
    void removesLeadingFileSequenceOnlyFromProjectRequirementChapterName() {
        ProjectMaterialExportService service = new ProjectMaterialExportService(
                mock(FileStorageService.class), mock(ExcelExportService.class),
                mock(WordExportService.class), tempDir.toString());

        assertEquals("家客终端管理系统周期盘点功能优化需求",
                service.normalizeRequirementChapterName("1.家客终端管理系统周期盘点功能优化需求"));
        assertEquals("家客终端库存管理优化需求",
                service.normalizeRequirementChapterName("2 家客终端库存管理优化需求"));
        assertEquals("新增终端临时管理模块需求说明书",
                service.normalizeRequirementChapterName("（13）新增终端临时管理模块需求说明书"));
        assertEquals("终端管理系统翻新管理功能优化需求说明书",
                service.normalizeRequirementChapterName("十一、终端管理系统翻新管理功能优化需求说明书"));
        assertEquals("2026年家客终端扩容需求说明书",
                service.normalizeRequirementChapterName("2026年家客终端扩容需求说明书"));
        assertEquals("5G终端管理需求说明书",
                service.normalizeRequirementChapterName("5G终端管理需求说明书"));
    }

    @Test
    void generatesSanitizedLedgerApplicationAndFiveFileArchive() throws Exception {
        FileStorageService storage = mock(FileStorageService.class);
        ExcelExportService excel = mock(ExcelExportService.class);
        WordExportService word = mock(WordExportService.class);
        ProjectMetadata first = metadata("P1", "1.需求甲.docx", rowFile("rows-1.json", 2D));
        ProjectMetadata second = metadata("P2", "13 需求乙.docx", rowFile("rows-2.json", 3D));
        when(storage.loadMetadata("P1")).thenReturn(first);
        when(storage.loadMetadata("P2")).thenReturn(second);

        Path generated = tempDir.resolve("generated-stubs");
        Files.createDirectories(generated);
        Path attachment1 = Files.write(generated.resolve("附件1.docx"), "docx".getBytes(StandardCharsets.UTF_8));
        Path attachment2 = Files.write(generated.resolve("附件2.xlsx"), "xlsx".getBytes(StandardCharsets.UTF_8));
        Path attachment3 = Files.write(generated.resolve("附件3.xlsx"), "xlsx".getBytes(StandardCharsets.UTF_8));
        ExcelExportService.ProjectExcelExportResult excelResult =
                mock(ExcelExportService.ProjectExcelExportResult.class);
        when(excelResult.getAttachment2Path()).thenReturn(attachment2);
        when(excelResult.getAttachment3Path()).thenReturn(attachment3);
        when(excelResult.getRequirementCount()).thenReturn(2);
        when(excelResult.getTotalCfp()).thenReturn(5D);
        when(excel.exportProjectMaterial(eq("BS1"), eq("备案项目"), anyList(),
                org.mockito.ArgumentMatchers.any(Path.class))).thenReturn(excelResult);
        WordExportService.ProjectWordExportResult wordResult =
                mock(WordExportService.ProjectWordExportResult.class);
        when(wordResult.getAttachment1Path()).thenReturn(attachment1);
        when(word.exportProjectMaterial(eq("BS1"), eq("备案项目"), anyList(),
                org.mockito.ArgumentMatchers.any(Path.class))).thenReturn(wordResult);

        ProjectMaterialExportService service = new ProjectMaterialExportService(
                storage, excel, word, "target/project-material-qa/forms");
        ProjectMaterialExportService.ProjectMaterialBundle bundle =
                service.export("BS1", Arrays.asList("P1", "P2"));

        assertEquals("备案项目", bundle.getProjectName());
        assertEquals(2, bundle.getRequirementCount());
        assertEquals(5D, bundle.getTotalCfp());
        verify(excel, never()).export("P1");
        verify(word, never()).export("P1");
        verify(excel, never()).export("P2");
        verify(word, never()).export("P2");
        ArgumentCaptor<java.util.List<FunctionSplitRow>> aggregateRows = ArgumentCaptor.forClass(java.util.List.class);
        verify(excel).exportProjectMaterial(eq("BS1"), eq("备案项目"), aggregateRows.capture(),
                org.mockito.ArgumentMatchers.any(Path.class));
        assertEquals("需求甲", aggregateRows.getValue().get(0).getLevel1Module());
        assertEquals("需求乙", aggregateRows.getValue().get(1).getLevel1Module());

        Path attachment4 = bundle.getFile(ProjectMaterialExportService.ATTACHMENT_4);
        try (InputStream input = Files.newInputStream(attachment4);
             Workbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            assertEquals("备案项目", formatter.formatCellValue(sheet.getRow(2).getCell(1)));
            assertEquals("5", formatter.formatCellValue(sheet.getRow(2).getCell(10)));
            assertEquals("5", formatter.formatCellValue(sheet.getRow(2).getCell(11)));
            assertEquals("", formatter.formatCellValue(sheet.getRow(2).getCell(6)));
            assertFalse(formatter.formatCellValue(sheet.getRow(2).getCell(14)).contains("毛钰"));
        }

        Path attachment5 = bundle.getFile(ProjectMaterialExportService.ATTACHMENT_5);
        try (InputStream input = Files.newInputStream(attachment5);
             Workbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            assertEquals("", formatter.formatCellValue(sheet.getRow(1).getCell(1)));
            assertEquals("备案项目", formatter.formatCellValue(sheet.getRow(2).getCell(1)));
            assertEquals("5人天", formatter.formatCellValue(sheet.getRow(4).getCell(1)));
            assertEquals("", formatter.formatCellValue(sheet.getRow(5).getCell(1)));
            assertFalse(formatter.formatCellValue(sheet.getRow(6).getCell(1)).contains("中移物联网"));
        }

        Set<String> entries = new HashSet<String>();
        try (InputStream input = Files.newInputStream(bundle.getFile(ProjectMaterialExportService.ARCHIVE));
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.add(entry.getName());
        }
        assertEquals(5, entries.size());
        assertTrue(entries.stream().anyMatch(name -> name.endsWith(".docx")));
        assertEquals(4, entries.stream().filter(name -> name.endsWith(".xlsx")).count());
    }

    private ProjectMetadata metadata(String id, String sourceName, Path rowsPath) {
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(id);
        metadata.setName("备案项目");
        metadata.setSourceFileName(sourceName);
        metadata.setFileType("docx");
        metadata.setFunctionSplitRowsPath(rowsPath.toString());
        return metadata;
    }

    private Path rowFile(String name, double cfp) throws Exception {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setLevel1Module("业务模块");
        row.setLevel2Module("功能模块");
        row.setLevel3Module("查询功能");
        row.setFunctionProcess("查询数据");
        row.setSubProcessDescription("接收查询请求");
        row.setMovementType("E");
        row.setDataGroup("查询请求");
        row.setDataAttributes("请求编号");
        row.setReuseDegree("新增");
        row.setCfp(cfp);
        Path path = tempDir.resolve(name);
        new ObjectMapper().writeValue(path.toFile(), Collections.singletonList(row));
        return path;
    }
}
