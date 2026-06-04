package com.example.cosmiccostai.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiExtractionServiceSectionTest {

    @Test
    void extractsNonStandardRequirementRuleSection() throws Exception {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "", "", "test-model", 1, 0);
        Method method = AiExtractionService.class.getDeclaredMethod("extractRequirementDetail", String.class);
        method.setAccessible(true);

        String text = "一.背景及目标\n"
                + "这里是背景说明。\n"
                + "二.归属系统\n"
                + "宽带运维支撑平台\n"
                + "三.需求规则\n"
                + "（1）统计规则优化：\n"
                + "按照原有既定规则，当匹配规则执行至标准十级地址易重环节时，更新为标准十级地址ID字段。\n"
                + "四.非功能性需求\n"
                + "性能要求。";

        String extracted = (String) method.invoke(service, text);
        assertTrue(extracted.contains("统计规则优化"));
        assertTrue(extracted.contains("标准十级地址ID"));
        assertFalse(extracted.contains("性能要求"));
    }
}
