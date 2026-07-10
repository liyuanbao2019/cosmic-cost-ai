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

    @Test
    void previewPreservesRequirementDetailLineBreaks() throws Exception {
        DocumentParseService service = new DocumentParseService(new FileStorageService("storage"));
        Method method = DocumentParseService.class.getDeclaredMethod("buildPreview", String.class);
        method.setAccessible(true);

        String preview = (String) method.invoke(service, "line one\nline two\nline three");

        assertTrue(preview.contains("line one\nline two\nline three"));
    }

    @Test
    void previewSkipsOwnershipMetadataBeforeFunctionDescription() throws Exception {
        DocumentParseService service = new DocumentParseService(new FileStorageService("storage"));
        Method method = DocumentParseService.class.getDeclaredMethod("extractRequirementDetailPreview", String.class);
        method.setAccessible(true);

        String text = "背景及目标\n"
                + "这里是背景说明。\n"
                + "需求细则\n"
                + "归属系统\n"
                + "家客终端管理平台\n"
                + "功能描述\n"
                + "赔付信息填报模块\n"
                + "支持创建新赔付工单。\n"
                + "功能界面\n"
                + "这里是界面说明。";

        String preview = (String) method.invoke(service, text);

        assertTrue(preview.contains("赔付信息填报模块"));
        assertTrue(preview.contains("支持创建新赔付工单"));
        assertFalse(preview.contains("归属系统"));
        assertFalse(preview.contains("家客终端管理平台"));
        assertFalse(preview.contains("功能界面"));
    }

    @Test
    void previewExtractsStandardAttachmentFunctionRequirementSection() throws Exception {
        DocumentParseService service = new DocumentParseService(new FileStorageService("storage"));
        Method method = DocumentParseService.class.getDeclaredMethod("extractRequirementDetailPreview", String.class);
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

        String preview = (String) method.invoke(service, text);

        assertTrue(preview.contains("一级需求模块标题"));
        assertTrue(preview.contains("支持创建新赔付工单"));
        assertFalse(preview.contains("背景说明"));
        assertFalse(preview.contains("性能要求"));
    }
}
