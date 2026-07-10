package com.example.cosmiccostai.service;

import com.example.cosmiccostai.dto.RequirementCoverageResponse;
import com.example.cosmiccostai.model.ProjectMetadata;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementCoverageServiceTest {

    @Test
    void reportsMissingModulesAndCanSupplementThemBeforeAiExtraction() throws Exception {
        FileStorageService storage = new FileStorageService("target/test-coverage-storage");
        String projectId = "coverage-project";
        Path projectDir = storage.resolveProjectDir(projectId);
        Files.createDirectories(projectDir);
        Path documentText = projectDir.resolve("document-text.txt");
        Path aiInput = projectDir.resolve("requirement-detail.txt");
        Files.write(documentText, ("1. 软件概述\n"
                + "2. 业务功能需求\n"
                + "2.1.1 智能报告管理（新增）\n2.1.1.1 功能描述\n新增、查询智能报告。\n2.1.1.2 功能界面\n"
                + "2.1.2 智能报告详情管理（新增）\n2.1.2.1 功能描述\n3. 报告详情展示：展示完整报告信息。\n维护报告详情。\n2.1.2.2 功能界面\n"
                + "2.1.3 终端盘点管理（新增）\n2.1.3.1 功能描述\n查询盘点结果。\n"
                + "3. 调整因子情况说明\n").getBytes(StandardCharsets.UTF_8));
        Files.write(aiInput, ("2.1.1 智能报告管理（新增）\n功能描述\n新增、查询智能报告。\n")
                .getBytes(StandardCharsets.UTF_8));
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(projectId);
        metadata.setDocumentTextPath(documentText.toString());
        metadata.setRequirementDetailPath(aiInput.toString());
        storage.saveMetadata(metadata);

        RequirementCoverageService service = new RequirementCoverageService(storage);
        RequirementCoverageResponse before = service.getCoverage(projectId);
        assertEquals(3, before.getSourceModuleCount());
        assertEquals(1, before.getIncludedModuleCount());
        assertEquals(2, before.getMissingModuleCount());
        assertFalse(before.isReadyForExtraction());

        RequirementCoverageResponse after = service.includeModules(projectId, Arrays.asList("2.1.2", "2.1.3"));
        assertEquals(3, after.getIncludedModuleCount());
        assertTrue(after.isReadyForExtraction());
    }
}
