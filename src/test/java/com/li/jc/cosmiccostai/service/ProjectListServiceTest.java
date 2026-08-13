package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.cosmiccostai.dto.ProjectListResponse;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectListServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void filtersAndPaginatesFromLightweightMetadata() throws Exception {
        writeProject("P1", "备案项目A", "PROJECT", "B1", "2026-08-12 10:00:00");
        writeProject("P2", "备案项目A", "REQUIREMENT", null, "2026-08-12 09:00:00");
        writeProject("P3", "备案项目B", "REQUIREMENT", null, "2026-08-12 08:00:00");

        ProjectListService service = new ProjectListService(tempDir.toString(), new RuleValidationService());
        ProjectListResponse response = service.listProjects(null, "备案项目A", "PROJECT",
                null, null, null, null, 1, 1);

        assertEquals(3, response.getOverallTotal());
        assertEquals(1, response.getTotal());
        assertEquals(1, response.getProjects().size());
        assertEquals("P1", response.getProjects().get(0).getProjectId());
        assertEquals("B1", response.getProjects().get(0).getBatchId());
    }

    @Test
    void restoresHistoricalSplitLevelAssociationFromBatchState() throws Exception {
        writeProject("P4", "备案项目A", null, null, "2026-08-12 07:00:00");
        Path batchDir = tempDir.resolve("batch-split").resolve("BS1");
        Files.createDirectories(batchDir);
        Files.write(batchDir.resolve("state.json"),
                ("{\"batchId\":\"BS1\",\"splitLevel\":\"PROJECT\",\"items\":[{\"projectId\":\"P4\"}]}")
                        .getBytes(StandardCharsets.UTF_8));

        ProjectListService service = new ProjectListService(tempDir.toString(), new RuleValidationService());
        ProjectListResponse response = service.listProjects(null, null, "PROJECT",
                null, null, null, null, 1, 20);

        assertEquals(1, response.getTotal());
        assertEquals("PROJECT", response.getProjects().get(0).getSplitLevel());
        assertEquals("BS1", response.getProjects().get(0).getBatchId());
    }

    @Test
    void statisticsUseTheSameFilteredDataSet() throws Exception {
        writeProject("P5", "备案项目A", "REQUIREMENT", null, "2026-08-12 06:00:00", 2, true);
        writeProject("P6", "备案项目B", "REQUIREMENT", null, "2026-08-12 05:00:00", 3, true);

        ProjectListService service = new ProjectListService(tempDir.toString(), new RuleValidationService());
        ProjectListResponse response = service.listProjects(null, "备案项目A", null,
                null, null, null, null, 1, 20);

        assertEquals(1, response.getTotal());
        assertEquals(1, response.getTotalWithRisks());
        assertEquals(1, response.getTotalExported());
    }

    @Test
    void exposesWhetherRequirementHasReusableFinalSplitRows() throws Exception {
        writeProject("P7", "备案项目A", "REQUIREMENT", null, "2026-08-12 04:00:00");
        Path metadataPath = tempDir.resolve("projects").resolve("P7").resolve("metadata.json");
        ObjectMapper mapper = new ObjectMapper();
        ProjectMetadata metadata = mapper.readValue(metadataPath.toFile(), ProjectMetadata.class);
        Path rows = metadataPath.getParent().resolve("function-split-rows.json");
        Files.write(rows, "[]".getBytes(StandardCharsets.UTF_8));
        metadata.setFunctionSplitRowsPath(rows.toString());
        mapper.writeValue(metadataPath.toFile(), metadata);

        ProjectListService service = new ProjectListService(tempDir.toString(), new RuleValidationService());
        ProjectListResponse response = service.listProjects(null, null, "REQUIREMENT",
                null, null, null, null, 1, 20);

        assertEquals(1, response.getTotal());
        assertTrue(response.getProjects().get(0).isSplitResultReady());
    }

    private void writeProject(String id, String name, String splitLevel, String batchId, String updatedAt) throws Exception {
        writeProject(id, name, splitLevel, batchId, updatedAt, 0, false);
    }

    private void writeProject(String id, String name, String splitLevel, String batchId, String updatedAt,
                              int issueCount, boolean exported) throws Exception {
        Path dir = tempDir.resolve("projects").resolve(id);
        Files.createDirectories(dir);
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(id);
        metadata.setName(name);
        metadata.setSplitLevel(splitLevel);
        metadata.setBatchId(batchId);
        metadata.setSourceFileName(id + ".docx");
        metadata.setStoredFilePath(dir.resolve("original.docx").toString());
        metadata.setStatus("REVIEWED");
        metadata.setCreatedAt(updatedAt);
        metadata.setUpdatedAt(updatedAt);
        metadata.setFunctionCount(1);
        metadata.setIssueCount(issueCount);
        metadata.setErrorCount(0);
        metadata.setRiskCount(issueCount);
        metadata.setSuggestionCount(0);
        if (exported) {
            Path excel = dir.resolve("export.xlsx");
            Path business = dir.resolve("business.xlsx");
            Path word = dir.resolve("export.docx");
            Files.createFile(excel);
            Files.createFile(business);
            Files.createFile(word);
            metadata.setExcelOutputPath(excel.toString());
            metadata.setBusinessRequirementOutputPath(business.toString());
            metadata.setWordOutputPath(word.toString());
        }
        new ObjectMapper().writeValue(dir.resolve("metadata.json").toFile(), metadata);
    }
}
