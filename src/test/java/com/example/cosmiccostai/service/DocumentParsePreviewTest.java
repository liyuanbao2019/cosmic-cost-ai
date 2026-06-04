package com.example.cosmiccostai.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParsePreviewTest {

    @Test
    void previewOnlyShowsRequirementDetailContent() throws Exception {
        DocumentParseService service = new DocumentParseService(new FileStorageService("storage"));
        Method method = DocumentParseService.class.getDeclaredMethod("extractRequirementDetailPreview", String.class);
        method.setAccessible(true);

        String text = "一.背景及目标\n"
                + "这里是背景说明。\n"
                + "二.归属系统\n"
                + "宽带业务系统\n"
                + "三.业务功能\n"
                + "（1）统计规则优化：\n"
                + "按照原有既定规则，变更为以标准十级地址ID字段进行剔重。\n"
                + "四.非功能性需求\n"
                + "性能要求。\n";

        String preview = (String) method.invoke(service, text);

        assertTrue(preview.contains("统计规则优化"));
        assertTrue(preview.contains("标准十级地址ID"));
        assertFalse(preview.contains("背景说明"));
        assertFalse(preview.contains("性能要求"));
    }
}
