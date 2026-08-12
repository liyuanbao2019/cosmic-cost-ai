package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.DocumentChapterItem;
import com.li.jc.cosmiccostai.dto.DocumentChapterSelectionResponse;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentChapterSelectionServiceTest {

    @Test
    void prefersWordOutlineAndKeepsWorkflowStepsInsideBusinessChapter() throws Exception {
        FileStorageService storage = new FileStorageService("target/test-outline-selection-storage");
        RequirementInputService inputService = new RequirementInputService(storage);
        String projectId = "outline-selection-project";
        Path projectDir = storage.resolveProjectDir(projectId);
        Files.createDirectories(projectDir);
        Path documentText = projectDir.resolve("document-text.txt");
        Path requirementInput = projectDir.resolve("requirement-detail.txt");
        String fullText = "文档标题\n软件概述\n背景。\n业务功能需求\n技术服务支撑情况上报流程\n"
                + "一、流程初始化与配置\n创建流程。\n二、任务分发与数据填报\n派发任务。\n"
                + "三、进度监控与数据汇总\n生成报告。\n调整因子情况说明\n调整内容。\n";
        Files.write(documentText, fullText.getBytes(StandardCharsets.UTF_8));
        Files.write(requirementInput, ("业务功能需求\n技术服务支撑情况上报流程\n创建流程。\n派发任务。\n生成报告。")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(projectDir.resolve("document-outline.tsv"), Arrays.asList(
                "1\t软件概述",
                "1\t业务功能需求",
                "2\t技术服务支撑情况上报流程",
                "1\t调整因子情况说明"), StandardCharsets.UTF_8);
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(projectId);
        metadata.setDocumentTextPath(documentText.toString());
        metadata.setRequirementDetailPath(requirementInput.toString());
        storage.saveMetadata(metadata);

        DocumentChapterSelectionResponse response = new DocumentChapterSelectionService(storage, inputService).get(projectId);
        assertEquals(4, response.getChapters().size()); // preface + three outline level-1 chapters
        assertEquals("业务功能需求", response.getChapters().get(2).getTitle());
        assertTrue(response.getChapters().get(2).getCharCount() > 50);
        assertFalse(hasChapter(response, "流程初始化"));
        assertFalse(hasChapter(response, "任务分发"));
    }

    @Test
    void selectsBodyChaptersWithoutTreatingNumberedActionsAsTopLevelHeadings() throws Exception {
        FileStorageService storage = new FileStorageService("target/test-chapter-selection-storage");
        RequirementInputService inputService = new RequirementInputService(storage);
        String projectId = "chapter-selection-project";
        Path projectDir = storage.resolveProjectDir(projectId);
        Files.createDirectories(projectDir);
        Path documentText = projectDir.resolve("document-text.txt");
        Path requirementInput = projectDir.resolve("requirement-detail.txt");
        String fullText = "目录\n"
                + "1. 软件概述 1\n2. 业务功能需求 2\n3. 调整因子情况说明 9\n"
                + "1. 软件概述\n背景内容。\n"
                + "2. 业务功能需求\n2.1 智能报告\n3. 报告详情展示：展示报告信息。\n查询、导出报告。\n"
                + "3. 调整因子情况说明\n调整因子内容。\n";
        Files.write(documentText, fullText.getBytes(StandardCharsets.UTF_8));
        Files.write(requirementInput, "2. 业务功能需求\n查询、导出报告。".getBytes(StandardCharsets.UTF_8));
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(projectId);
        metadata.setDocumentTextPath(documentText.toString());
        metadata.setRequirementDetailPath(requirementInput.toString());
        storage.saveMetadata(metadata);

        DocumentChapterSelectionService service = new DocumentChapterSelectionService(storage, inputService);
        DocumentChapterSelectionResponse response = service.get(projectId);
        assertEquals(4, response.getChapters().size()); // preface + chapters 1, 2 and 3
        assertEquals("软件概述", response.getChapters().get(1).getTitle());
        assertEquals("业务功能需求", response.getChapters().get(2).getTitle());
        assertTrue(response.getChapters().get(2).isSelected());
        assertFalse(hasChapter(response, "报告详情展示"));

        DocumentChapterItem business = response.getChapters().get(2);
        DocumentChapterSelectionResponse applied = service.apply(projectId, Collections.singletonList(business.getId()));
        assertTrue(applied.getAiInput().contains("查询、导出报告"));
        assertFalse(applied.getAiInput().contains("调整因子内容"));
    }

    @Test
    void skipsSingleDocumentTitleAndRecommendsOnlyHighestConfidenceChapter() throws Exception {
        FileStorageService storage = new FileStorageService("target/test-wrapper-title-selection-storage");
        RequirementInputService inputService = new RequirementInputService(storage);
        String projectId = "wrapper-title-selection-project";
        Path projectDir = storage.resolveProjectDir(projectId);
        Files.createDirectories(projectDir);
        Path documentText = projectDir.resolve("document-text.txt");
        Path requirementInput = projectDir.resolve("requirement-detail.txt");
        String fullText = "家客终端管理系统接口需求说明书\n"
                + "1需求背景\n背景内容。\n"
                + "2需求目标\n目标内容。\n"
                + "3需求描述\n3.1归属系统\n家客终端管理系统\n"
                + "3.2业务规则\n完成接口数据推送、失败重试和日志查询。\n";
        Files.write(documentText, fullText.getBytes(StandardCharsets.UTF_8));
        Files.write(requirementInput, ("3.1归属系统\n家客终端管理系统\n"
                + "3.2业务规则\n完成接口数据推送、失败重试和日志查询。")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(projectDir.resolve("document-outline.tsv"), Arrays.asList(
                "1\t家客终端管理系统接口需求说明书",
                "2\t1需求背景",
                "2\t2需求目标",
                "2\t3需求描述",
                "3\t3.1归属系统",
                "3\t3.2业务规则"), StandardCharsets.UTF_8);
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(projectId);
        metadata.setDocumentTextPath(documentText.toString());
        metadata.setRequirementDetailPath(requirementInput.toString());
        storage.saveMetadata(metadata);

        DocumentChapterSelectionResponse response =
                new DocumentChapterSelectionService(storage, inputService).get(projectId);

        assertEquals(3, response.getChapters().size());
        assertEquals("需求背景", response.getChapters().get(0).getTitle());
        assertEquals("需求目标", response.getChapters().get(1).getTitle());
        assertEquals("需求描述", response.getChapters().get(2).getTitle());
        assertFalse(hasChapter(response, "接口需求说明书"));
        assertTrue(response.getChapters().get(2).getCharCount() > 40);
        assertEquals(1, selectedCount(response));
        assertEquals(1, recommendedCount(response));
        assertTrue(response.getChapters().get(2).isSelected());
        assertTrue(response.getChapters().get(2).isRecommended());
    }

    private boolean hasChapter(DocumentChapterSelectionResponse response, String title) {
        for (DocumentChapterItem chapter : response.getChapters()) {
            if (chapter.getTitle().contains(title)) {
                return true;
            }
        }
        return false;
    }

    private int selectedCount(DocumentChapterSelectionResponse response) {
        int count = 0;
        for (DocumentChapterItem chapter : response.getChapters()) {
            if (chapter.isSelected()) {
                count++;
            }
        }
        return count;
    }

    private int recommendedCount(DocumentChapterSelectionResponse response) {
        int count = 0;
        for (DocumentChapterItem chapter : response.getChapters()) {
            if (chapter.isRecommended()) {
                count++;
            }
        }
        return count;
    }
}
