package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.RequirementChapterCoverage;
import com.li.jc.cosmiccostai.model.CosmicMovement;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementHierarchyServiceTest {

    @Test
    void keepsBusinessTitlesThatStartWithAYear() throws Exception {
        FileStorageService storage = new FileStorageService("target/test-requirement-year-title-storage");
        String projectId = "year-title-project";
        Path projectDir = storage.resolveProjectDir(projectId);
        Files.createDirectories(projectDir);
        Files.write(projectDir.resolve("document-outline.tsv"), Arrays.asList(
                "1\t需求说明书",
                "2\t3需求描述",
                "3\t2026年运维管理扩容"), StandardCharsets.UTF_8);

        String detail = "3需求描述\n2026年运维管理扩容\n新增运维容量。";
        ExtractedFunction function = new ExtractedFunction();
        function.setSourceReferences(Collections.singletonList("REQ-L0003"));

        RequirementHierarchyService service = new RequirementHierarchyService(storage);
        service.enrichFunctions(projectId, detail, Collections.singletonList(function));

        assertEquals("2026年运维管理扩容", function.getLevel1Module());
    }

    @Test
    void resolvesMovementHierarchyFromWordOutlineAndKeepsCrossChapterTraceability() throws Exception {
        FileStorageService storage = new FileStorageService("target/test-requirement-hierarchy-storage");
        String projectId = "hierarchy-project";
        Path projectDir = storage.resolveProjectDir(projectId);
        Files.createDirectories(projectDir);
        Files.write(projectDir.resolve("document-outline.tsv"), Arrays.asList(
                "1\t政企终端库存情况月度通报自动生成需求说明书",
                "2\t3需求描述",
                "3\t3.1月度基础数据自动运算规则",
                "3\t3.2七类基础数据报表",
                "4\t3.2.1库存基础数据报表",
                "3\t3.3四大分项通报自动生成逻辑",
                "4\t3.3.1分项一：库存整体情况通报",
                "5\t一、内置自动生成统计表",
                "3\t3.8分权分域权限管控",
                "3\t3.9异常重试与告警机制",
                "3\t3.10数据存储与审计留存"), StandardCharsets.UTF_8);

        String detail = "3需求描述\n"
                + "3.1月度基础数据自动运算规则\n"
                + "生成月度基础数据快照。\n"
                + "3.2七类基础数据报表\n"
                + "3.2.1库存基础数据报表\n"
                + "分页查询库存数据。\n"
                + "3.3四大分项通报自动生成逻辑\n"
                + "3.3.1分项一：库存整体情况通报\n"
                + "一、内置自动生成统计表\n"
                + "生成库存统计表。\n"
                + "3.8分权分域权限管控\n"
                + "地市管理员仅能查看本市数据，查询时按管辖地市过滤。\n"
                + "3.9异常重试与告警机制\n"
                + "生成失败时记录失败原因并自动重试，连续失败后触发告警。\n"
                + "3.10数据存储与审计留存\n"
                + "全部数据永久存储，系统不支持人工删除。";

        ExtractedFunction function = new ExtractedFunction();
        function.setModule("模型自行概括名称");
        function.setFunction("定时生成月度通报全流程");
        function.setSourceReferences(Arrays.asList("REQ-L0003", "REQ-L0010"));
        CosmicMovement query = movement("REQ-L0006");
        CosmicMovement report = movement("REQ-L0010");
        function.setMovements(Arrays.asList(query, report));

        RequirementHierarchyService service = new RequirementHierarchyService(storage);
        service.enrichFunctions(projectId, detail, Collections.singletonList(function));

        assertEquals("月度基础数据自动运算规则", function.getLevel1Module());
        assertEquals("", function.getLevel2Module());
        assertEquals("月度基础数据自动运算规则", function.getModule());
        assertEquals(2, function.getSourceHeadingPaths().size());
        assertEquals("七类基础数据报表", query.getLevel1Module());
        assertEquals("库存基础数据报表", query.getLevel2Module());
        assertEquals("四大分项通报自动生成逻辑", report.getLevel1Module());
        assertEquals("分项一：库存整体情况通报", report.getLevel2Module());
        assertEquals("一、内置自动生成统计表", report.getLevel3Module());

        String annotated = service.annotateRequirement(projectId, detail);
        assertTrue(annotated.contains("[REQ-L0006][原文路径：需求描述 > 七类基础数据报表 > 库存基础数据报表]"));

        List<RequirementChapterCoverage> coverage = service.validateAndWriteCoverage(
                projectId, detail, Collections.singletonList(function));
        assertEquals(6, coverage.size());
        assertEquals("CONSTRAINT_NOT_INDEPENDENT", coverage.get(3).getStatus());
        assertEquals("MISSING_REVIEW", coverage.get(4).getStatus());
        assertTrue(coverage.get(4).getReason().contains("不阻断"));
        assertEquals("CONSTRAINT_NOT_INDEPENDENT", coverage.get(5).getStatus());
        assertTrue(Files.exists(projectDir.resolve("requirement-extraction-coverage.json")));

        Path detailPath = projectDir.resolve("requirement-detail.txt");
        Files.write(detailPath, detail.getBytes(StandardCharsets.UTF_8));
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(projectId);
        metadata.setRequirementDetailPath(detailPath.toString());
        FunctionSplitRow legacyRow = new FunctionSplitRow();
        legacyRow.setLevel1Module("库存基础数据报表");
        legacyRow.setLevel2Module("库存基础数据报表");
        legacyRow.setLevel3Module("库存基础数据报表");
        legacyRow.setSourceReferences(Collections.singletonList("REQ-L0006"));
        service.enrichRows(metadata, Collections.singletonList(legacyRow));
        assertEquals("七类基础数据报表", legacyRow.getLevel1Module());
        assertEquals("库存基础数据报表", legacyRow.getLevel2Module());
        assertEquals("", legacyRow.getLevel3Module());
    }

    private CosmicMovement movement(String reference) {
        CosmicMovement movement = new CosmicMovement();
        movement.setSourceReferences(Collections.singletonList(reference));
        return movement;
    }
}
