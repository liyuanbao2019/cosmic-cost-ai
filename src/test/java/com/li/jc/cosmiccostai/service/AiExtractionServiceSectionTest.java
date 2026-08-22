package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.cosmiccostai.dto.ExtractResponse;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiExtractionServiceSectionTest {

    @Test
    void cacheMatchIgnoresChangedDeepSeekReminderAndPromptRules() throws Exception {
        Path storagePath = Files.createTempDirectory("extract-cache-");
        FileStorageService storage = new FileStorageService(storagePath.toString());
        Path projectDir = storage.resolveProjectDir("P-CACHE-REMINDER");
        Files.createDirectories(projectDir);
        String requirement = "[REQ-L0001][原文路径：功能需求 > 第一章] 第一章正文";
        String oldPrompt = "旧规则\n【需求细则】\n" + requirement
                + "\n\n【DeepSeek 最终输出提醒】\n旧提醒";
        String newPrompt = "新规则\n【需求细则】\n" + requirement
                + "\n\n【DeepSeek 最终输出提醒】\n新提醒";
        String response = "{\"functions\":[{\"module\":\"第一章\",\"function\":\"查询结果\"}]}";
        Files.write(projectDir.resolve("ai-prompt-part-1.txt"), oldPrompt.getBytes(StandardCharsets.UTF_8));
        Files.write(projectDir.resolve("ai-response-part-1.txt"), response.getBytes(StandardCharsets.UTF_8));
        AiExtractionService service = new AiExtractionService(storage,
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        Method method = AiExtractionService.class.getDeclaredMethod(
                "cachedPartitionResponse", Path.class, String.class);
        method.setAccessible(true);

        String cached = (String) method.invoke(service, projectDir, newPrompt);

        assertEquals(response, cached);
        assertTrue(Files.isDirectory(projectDir.resolve("ai-part-cache")));
    }

    @Test
    void reusesMergedExtractionOnlyWhenInputHashMatches() throws Exception {
        Path storagePath = Files.createTempDirectory("extract-checkpoint-");
        FileStorageService storage = new FileStorageService(storagePath.toString());
        String projectId = "P-CHECKPOINT";
        Path projectDir = storage.resolveProjectDir(projectId);
        Files.createDirectories(projectDir);
        String requirement = "需求功能\n支持查询结果。";
        Path detailPath = projectDir.resolve("requirement-detail.txt");
        Path documentPath = projectDir.resolve("document-text.txt");
        Files.write(detailPath, requirement.getBytes(StandardCharsets.UTF_8));
        Files.write(documentPath, requirement.getBytes(StandardCharsets.UTF_8));

        ExtractedFunction function = new ExtractedFunction("查询", "查询结果", "返回查询结果");
        new ObjectMapper().writeValue(projectDir.resolve("extracted-functions.json").toFile(),
                Collections.singletonList(function));
        AiExtractionService service = new AiExtractionService(storage,
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        Method hashMethod = AiExtractionService.class.getDeclaredMethod("sha256", String.class);
        hashMethod.setAccessible(true);
        String hash = (String) hashMethod.invoke(service, requirement);
        Files.write(projectDir.resolve("extracted-functions-input.sha256"),
                hash.getBytes(StandardCharsets.UTF_8));

        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(projectId);
        metadata.setName("checkpoint");
        metadata.setDocumentTextPath(documentPath.toString());
        metadata.setRequirementDetailPath(detailPath.toString());
        metadata.setStatus("AI_FAILED");
        storage.saveMetadata(metadata);

        ExtractResponse result = service.extract(projectId);

        assertEquals(1, result.getFunctionCount());
        assertEquals("EXTRACTED", result.getStatus());
        assertEquals("查询结果", result.getFunctions().get(0).getFunction());
    }

    @Test
    void preservesExistingCompletePartitionPlanForCacheReuse() throws Exception {
        Path storagePath = Files.createTempDirectory("extract-plan-");
        FileStorageService storage = new FileStorageService(storagePath.toString());
        Path projectDir = storage.resolveProjectDir("P-CACHE");
        Files.createDirectories(projectDir);
        Files.write(projectDir.resolve("ai-partition-count.txt"), "2".getBytes(StandardCharsets.UTF_8));
        String first = "[REQ-L0001][原文路径：功能需求 > 第一章] 第一章";
        String second = "[REQ-L0002][原文路径：功能需求 > 第二章] 第二章";
        Files.write(projectDir.resolve("ai-prompt-part-1.txt"),
                ("提示\n【需求细则】\n" + first + "\n\n【DeepSeek 最终输出提醒】\n提醒")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(projectDir.resolve("ai-prompt-part-2.txt"),
                ("提示\n【需求细则】\n" + second + "\n\n【DeepSeek 最终输出提醒】\n提醒")
                        .getBytes(StandardCharsets.UTF_8));
        AiExtractionService service = new AiExtractionService(storage,
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        Method method = AiExtractionService.class.getDeclaredMethod(
                "reusablePartitionPlan", Path.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> parts = (List<String>) method.invoke(service, projectDir, first + "\n" + second);

        assertEquals(Arrays.asList(first, second), parts);
    }

    @Test
    void autoPartitionUsesMajorChaptersThenCompleteSourceLines() {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        String largeChapter = repeat("甲", 80000);
        String mediumChapter = repeat("乙", 20000);
        String annotated = "[REQ-L0001][原文路径：功能需求] 功能需求\n"
                + "[REQ-L0002][原文路径：功能需求 > 3.1智能分析] 3.1智能分析\n"
                + "[REQ-L0003][原文路径：功能需求 > 3.1智能分析 > 功能说明] 功能说明\n"
                + "[REQ-L0004][原文路径：功能需求 > 3.1智能分析 > 功能说明 > 问题识别] " + largeChapter + "\n"
                + "[REQ-L0005][原文路径：功能需求 > 3.2智能问数查询] 3.2智能问数查询\n"
                + "[REQ-L0006][原文路径：功能需求 > 3.2智能问数查询 > 功能说明] " + mediumChapter + "\n"
                + "[REQ-L0007][原文路径：功能需求 > 3.3知识库搭建] 3.3知识库搭建\n"
                + "[REQ-L0008][原文路径：功能需求 > 3.3知识库搭建 > 功能说明] " + mediumChapter + "\n";

        List<String> parts = service.splitAnnotatedRequirement(annotated);

        assertEquals(6, parts.size());
        assertTrue(parts.get(0).contains("3.1智能分析"));
        assertTrue(parts.get(1).contains("问题识别"));
        assertFalse(parts.get(1).contains("3.2智能问数查询"));
        assertTrue(parts.get(2).startsWith("[REQ-L0005]"));
        assertTrue(parts.get(2).contains("3.2智能问数查询"));
        assertTrue(parts.get(3).contains(mediumChapter));
    }

    @Test
    void autoPartitionCreatesAdditionalPartsWithoutBreakingLargeChapters() {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        String annotated = "[REQ-L0001][原文路径：功能需求 > 3.1智能分析] " + repeat("甲", 80000) + "\n"
                + "[REQ-L0002][原文路径：功能需求 > 3.2智能问数查询] " + repeat("乙", 40000) + "\n"
                + "[REQ-L0003][原文路径：功能需求 > 3.3知识库搭建] " + repeat("丙", 65000) + "\n";

        List<String> parts = service.splitAnnotatedRequirement(annotated);

        assertEquals(3, parts.size());
        assertTrue(parts.get(0).contains("3.1智能分析"));
        assertTrue(parts.get(1).contains("3.2智能问数查询"));
        assertTrue(parts.get(2).contains("3.3知识库搭建"));
    }

    @Test
    void oversizedMajorChapterPacksOnlyAtRealNestedHeadingBoundaries() {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        String annotated = "[REQ-L0001][原文路径：功能需求 > 3.9网优能力封装] 3.9网优能力封装\n"
                + "[REQ-L0002][原文路径：功能需求 > 3.9网优能力封装 > 功能说明 > 指标查询能力] "
                + repeat("甲", 8000) + "\n"
                + "[REQ-L0003][原文路径：功能需求 > 3.9网优能力封装 > 功能说明 > 质差分析能力] "
                + repeat("乙", 8000) + "\n"
                + "[REQ-L0004][原文路径：功能需求 > 3.9网优能力封装 > 功能说明 > 告警分析能力] "
                + repeat("丙", 8000) + "\n";

        List<String> parts = service.splitAnnotatedRequirement(annotated);

        assertEquals(2, parts.size());
        assertTrue(parts.get(0).contains("指标查询能力"));
        assertTrue(parts.get(0).contains("质差分析能力"));
        assertFalse(parts.get(0).contains("告警分析能力"));
        assertTrue(parts.get(1).contains("告警分析能力"));
    }

    @Test
    void autoPartitionKeepsSingleRequestWhenNoMajorChapterBoundaryExists() {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        String annotated = "[REQ-L0001][原文路径：功能需求 > 3.1智能分析] 3.1智能分析\n"
                + "[REQ-L0002][原文路径：功能需求 > 3.1智能分析 > 功能说明] 功能说明\n"
                + "[REQ-L0003][原文路径：功能需求 > 3.1智能分析 > 功能说明 > 问题识别] 正文\n";

        List<String> parts = service.splitAnnotatedRequirement(annotated);

        assertEquals(1, parts.size());
        assertEquals(annotated.trim(), parts.get(0));
    }

    @Test
    void extractsNonStandardRequirementRuleSection() throws Exception {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        Method method = AiExtractionService.class.getDeclaredMethod("extractRequirementDetail", String.class);
        method.setAccessible(true);

        String text = "一.背景及目标\n"
                + "这里是背景说明。\n"
                + "二.归属系统\n"
                + "宽带运维支撑平台\n"
                + "三.业务功能\n"
                + "（1）统计规则优化：\n"
                + "按照原有既定规则，当匹配规则执行至标准十级地址易重环节时，更新为标准十级地址ID字段。\n"
                + "四.非功能性需求\n"
                + "性能要求。";

        String extracted = (String) method.invoke(service, text);
        assertTrue(extracted.contains("统计规则优化"));
        assertTrue(extracted.contains("标准十级地址ID"));
        assertFalse(extracted.contains("性能要求"));
    }

    @Test
    void extractsCompleteNumberedBusinessRequirementChapterWithoutDroppingUiAndFlowText() throws Exception {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        Method method = AiExtractionService.class.getDeclaredMethod("extractRequirementDetail", String.class);
        method.setAccessible(true);

        String text = "二.业务功能\n"
                + "2.1 串实不符检测优化\n"
                + "这是一段摘要，也要保留在完整章节中。\n"
                + "2.1.1 串实不符检测优化（新增）\n"
                + "2.1.1.1 功能描述\n"
                + "串实不符管理者视图数据查询：在 PC 客户端，通过输入查询条件，查询对应串实不符统计数据。\n"
                + "串实不符明细数据检测：在 PC 客户端，点击检测按钮调用RMS接口核实串实结果。\n"
                + "2.1.1.2 功能界面\n"
                + "这里是界面说明，也要保留在完整章节中。\n"
                + "2.1.1.3 流程图\n"
                + "三.调整因子情况说明\n"
                + "规模变更调整因子";

        String extracted = (String) method.invoke(service, text);

        assertTrue(extracted.contains("串实不符管理者视图数据查询"));
        assertTrue(extracted.contains("串实不符明细数据检测"));
        assertTrue(extracted.contains("这是一段摘要"));
        assertTrue(extracted.contains("功能界面"));
        assertTrue(extracted.contains("流程图"));
        assertFalse(extracted.contains("调整因子"));
    }

    @Test
    void extractsRequirementDescriptionAfterOwnershipMetadata() throws Exception {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        Method method = AiExtractionService.class.getDeclaredMethod("extractRequirementDetail", String.class);
        method.setAccessible(true);

        String text = "背景及目标\n"
                + "这里是背景说明。\n"
                + "需求细则\n"
                + "归属系统\n"
                + "家客终端管理平台\n"
                + "需求描述\n"
                + "赔付信息填报模块\n"
                + "支持创建新赔付工单。\n"
                + "功能界面\n"
                + "这里是界面说明。";

        String extracted = (String) method.invoke(service, text);

        assertTrue(extracted.contains("赔付信息填报模块"));
        assertTrue(extracted.contains("支持创建新赔付工单"));
        assertFalse(extracted.contains("归属系统"));
        assertFalse(extracted.contains("家客终端管理平台"));
        assertFalse(extracted.contains("功能界面"));
    }

    @Test
    void extractsStandardAttachmentFunctionRequirementSection() throws Exception {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        Method method = AiExtractionService.class.getDeclaredMethod("extractRequirementDetail", String.class);
        method.setAccessible(true);

        String text = "背景及目标\n"
                + "背景说明。\n"
                + "需求归属系统\n"
                + "家客运维系统\n"
                + "功能需求\n"
                + "一级需求模块标题\n"
                + "二级需求模块标题\n"
                + "功能性用户需求标题1(请注明本需求是：新增、优化、删除）\n"
                + "支持创建新赔付工单并保存赔付记录。\n"
                + "非功能需求\n"
                + "性能要求。";

        String extracted = (String) method.invoke(service, text);

        assertTrue(extracted.contains("一级需求模块标题"));
        assertTrue(extracted.contains("支持创建新赔付工单"));
        assertFalse(extracted.contains("背景说明"));
        assertFalse(extracted.contains("性能要求"));
    }

    private String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
