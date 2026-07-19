package com.li.jc.cosmiccostai.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParseServiceDiagnosisTest {

    @Test
    void recognizesNonStandardRequirementSectionTitle() throws Exception {
        Object diagnosis = diagnose("一.背景及目标\n二.归属系统\n三.业务功能\n统计规则优化。");
        assertTrue((Boolean) field(diagnosis, "recognized"));
    }

    @Test
    void warnsWhenNoRequirementSectionTitleExists() throws Exception {
        Object diagnosis = diagnose("背景说明。\n按照原有既定规则优化字段去重逻辑。");
        assertFalse((Boolean) field(diagnosis, "recognized"));
        assertTrue(((String) field(diagnosis, "suggestion")).contains("需求细则"));
    }

    private Object diagnose(String text) throws Exception {
        DocumentParseService service = new DocumentParseService(new FileStorageService("storage"));
        Method method = DocumentParseService.class.getDeclaredMethod("diagnoseRequirementSection", String.class);
        method.setAccessible(true);
        return method.invoke(service, text);
    }

    private Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
