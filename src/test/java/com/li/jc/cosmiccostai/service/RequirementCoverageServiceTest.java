package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.RequirementCoverageResponse;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
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
    void acceptsCompleteModuleBodyWhenWordHeadingIsMissingFromAiInput() throws Exception {
        FileStorageService storage = new FileStorageService("target/test-heading-loss-coverage-storage");
        String projectId = "heading-loss-coverage-project";
        Path projectDir = storage.resolveProjectDir(projectId);
        Files.createDirectories(projectDir);
        Path documentText = projectDir.resolve("document-text.txt");
        Path aiInput = projectDir.resolve("requirement-detail.txt");
        String moduleBody = "权限控制：按地市进行数据权限隔离，用户仅可查看所属地市范围内的投诉数据。\n"
                + "FP-001 GIS底图与投诉问题点打点呈现\n"
                + "基于GIS地图引擎，将投诉工单按经纬度信息在底图上进行打点呈现。\n";
        Files.write(documentText, ("1. 功能需求\n1.1 需求描述\n" + moduleBody
                + "2. 非功能需求\n性能要求。\n").getBytes(StandardCharsets.UTF_8));
        Files.write(aiInput, moduleBody.getBytes(StandardCharsets.UTF_8));
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(projectId);
        metadata.setDocumentTextPath(documentText.toString());
        metadata.setRequirementDetailPath(aiInput.toString());
        storage.saveMetadata(metadata);

        RequirementCoverageResponse response = new RequirementCoverageService(storage).getCoverage(projectId);

        assertEquals(1, response.getSourceModuleCount());
        assertEquals(1, response.getIncludedModuleCount());
        assertEquals(0, response.getMissingModuleCount());
        assertTrue(response.isReadyForExtraction());
    }

    @Test
    void doesNotAcceptPartialBodyTextWhenHeadingIsMissing() throws Exception {
        FileStorageService storage = new FileStorageService("target/test-partial-body-coverage-storage");
        String projectId = "partial-body-coverage-project";
        Path projectDir = storage.resolveProjectDir(projectId);
        Files.createDirectories(projectDir);
        Path documentText = projectDir.resolve("document-text.txt");
        Path aiInput = projectDir.resolve("requirement-detail.txt");
        Files.write(documentText, ("1. 功能需求\n1.1 需求描述\n"
                + "权限控制：按地市进行数据权限隔离，用户仅可查看所属地市范围内的投诉数据。\n"
                + "GIS底图展示全部投诉问题点并支持按时间范围聚合。\n"
                + "2. 非功能需求\n性能要求。\n").getBytes(StandardCharsets.UTF_8));
        Files.write(aiInput, "权限控制：按地市进行数据权限隔离。\n".getBytes(StandardCharsets.UTF_8));
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(projectId);
        metadata.setDocumentTextPath(documentText.toString());
        metadata.setRequirementDetailPath(aiInput.toString());
        storage.saveMetadata(metadata);

        RequirementCoverageResponse response = new RequirementCoverageService(storage).getCoverage(projectId);

        assertEquals(1, response.getSourceModuleCount());
        assertEquals(0, response.getIncludedModuleCount());
        assertEquals(1, response.getMissingModuleCount());
        assertFalse(response.isReadyForExtraction());
    }

    @Test
    void recognizesRequirementDescriptionAndNoSpaceHeadingNumbers() throws Exception {
        FileStorageService storage = new FileStorageService("target/test-description-coverage-storage");
        String projectId = "description-coverage-project";
        Path projectDir = storage.resolveProjectDir(projectId);
        Files.createDirectories(projectDir);
        Path documentText = projectDir.resolve("document-text.txt");
        Path aiInput = projectDir.resolve("requirement-detail.txt");
        String source = "3需求描述\n"
                + "3.1月度基础数据自动运算规则\n生成月度快照。\n"
                + "3.2七类基础数据报表\n"
                + "3.2.1库存基础数据报表\n查询并导出库存。\n"
                + "3.8分权分域权限管控\n地市管理员仅查看本市数据。\n";
        Files.write(documentText, source.getBytes(StandardCharsets.UTF_8));
        Files.write(aiInput, source.substring(0, source.indexOf("3.8分权分域权限管控"))
                .getBytes(StandardCharsets.UTF_8));
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(projectId);
        metadata.setDocumentTextPath(documentText.toString());
        metadata.setRequirementDetailPath(aiInput.toString());
        storage.saveMetadata(metadata);

        RequirementCoverageResponse response = new RequirementCoverageService(storage).getCoverage(projectId);

        assertEquals(4, response.getSourceModuleCount());
        assertEquals(3, response.getIncludedModuleCount());
        assertEquals(1, response.getMissingModuleCount());
        assertFalse(response.isReadyForExtraction());
        assertEquals("3.8", response.getModules().get(3).getNumber());
    }

    @Test
    void usesWordOutlineForUnnumberedFunctionalModules() throws Exception {
        FileStorageService storage = new FileStorageService("target/test-outline-coverage-storage");
        String projectId = "outline-coverage-project";
        Path projectDir = storage.resolveProjectDir(projectId);
        Files.createDirectories(projectDir);
        Path documentText = projectDir.resolve("document-text.txt");
        Path aiInput = projectDir.resolve("requirement-detail.txt");
        String fullText = "业务功能需求\n技术服务支撑情况上报流程\n设备厂商后评估\n"
                + "评估流程管理模块\n功能描述\n创建评估。\n"
                + "清单配置管理模块\n功能描述\n配置清单。\n"
                + "技术服务支撑半年报\n功能描述\n查询和导出。\n"
                + "调整因子情况说明\n调整内容。\n";
        Files.write(documentText, fullText.getBytes(StandardCharsets.UTF_8));
        Files.write(aiInput, fullText.substring(0, fullText.indexOf("调整因子情况说明"))
                .getBytes(StandardCharsets.UTF_8));
        Files.write(projectDir.resolve("document-outline.tsv"), Arrays.asList(
                "1\t业务功能需求", "2\t技术服务支撑情况上报流程", "3\t设备厂商后评估",
                "4\t评估流程管理模块", "5\t功能描述", "4\t清单配置管理模块",
                "5\t功能描述", "3\t技术服务支撑半年报", "4\t功能描述", "1\t调整因子情况说明"),
                StandardCharsets.UTF_8);
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(projectId);
        metadata.setDocumentTextPath(documentText.toString());
        metadata.setRequirementDetailPath(aiInput.toString());
        storage.saveMetadata(metadata);

        RequirementCoverageResponse response = new RequirementCoverageService(storage).getCoverage(projectId);
        assertEquals(4, response.getSourceModuleCount());
        assertEquals(4, response.getIncludedModuleCount());
        assertTrue(response.isReadyForExtraction());
    }

    @Test
    void reportsMissingModulesAsAdvisoryAndCanSupplementThem() throws Exception {
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
        Files.write(aiInput, ("2.1.1. 智能报告管理（新增）\n功能描述\n新增、查询智能报告。\n")
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
        assertEquals("2.1.2", before.getModules().get(1).getNumber());
        assertEquals("2.1.3", before.getModules().get(2).getNumber());

        RequirementCoverageResponse after = service.includeModules(projectId, Arrays.asList("2.1.2", "2.1.3"));
        assertEquals(3, after.getIncludedModuleCount());
        assertTrue(after.isReadyForExtraction());
    }
}
