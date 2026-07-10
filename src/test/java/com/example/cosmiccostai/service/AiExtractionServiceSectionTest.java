package com.example.cosmiccostai.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiExtractionServiceSectionTest {

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
}
