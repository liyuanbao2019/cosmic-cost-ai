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
    void previewDoesNotTruncateLongRequirementContent() throws Exception {
        DocumentParseService service = new DocumentParseService(new FileStorageService("storage"));
        Method method = DocumentParseService.class.getDeclaredMethod("buildPreview", String.class);
        method.setAccessible(true);

        String tail = "最后一个业务功能必须保留";
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            longContent.append('x');
        }
        String preview = (String) method.invoke(service, longContent.append(tail).toString());

        assertTrue(preview.endsWith(tail));
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

    @Test
    void previewCollectsAllFunctionsAndSkipsOnlyUiAndFlowSubsections() throws Exception {
        DocumentParseService service = new DocumentParseService(new FileStorageService("storage"));
        Method method = DocumentParseService.class.getDeclaredMethod("extractRequirementDetailPreview", String.class);
        method.setAccessible(true);

        String text = "1. 软件概述（可以不写）\n"
                + "背景说明。\n"
                + "2. 业务功能需求\n"
                + "2.1回收功能优化\n"
                + "1、优先查询终端管理系统内的终端放装记录。\n"
                + "2.1.1 回收规则管理（新增）\n"
                + "2.1.1.1 功能描述\n"
                + "回收规则查询、新增、修改、删除和导出。\n"
                + "2.1.1.2 功能界面\n"
                + "界面截图说明不应进入需求文本。\n"
                + "2.1.1.3 流程图\n"
                + "流程图说明不应进入需求文本。\n"
                + "2.1.2 串码查询反填（新增）\n"
                + "2.1.2.1 功能描述\n"
                + "根据回收规则查询串码并反填代维和NGBOSS系统。\n"
                + "2.1.2.2 功能界面\n"
                + "2.1.2.3 流程图\n"
                + "2.1.3 日志查询（新增）\n"
                + "2.1.3.1 功能描述\n"
                + "根据拆机账号和日期查询操作日志。\n"
                + "3. 调整因子情况说明\n"
                + "不应进入需求文本。\n";

        String preview = (String) method.invoke(service, text);
        assertTrue(preview.contains("回收规则查询、新增、修改、删除和导出"), preview);
        assertTrue(preview.contains("串码查询反填"));
        assertTrue(preview.contains("查询操作日志"));
        assertTrue(preview.contains("优先查询终端管理系统"));
        assertTrue(preview.contains("界面截图说明"));
        assertTrue(preview.contains("流程图说明"));
        assertFalse(preview.contains("调整因子"));
    }

    @Test
    void wholeBusinessChapterIsNotCutOffByNumberedBusinessAction() throws Exception {
        DocumentParseService service = new DocumentParseService(new FileStorageService("storage"));
        Method method = DocumentParseService.class.getDeclaredMethod("extractRequirementDetailPreview", String.class);
        method.setAccessible(true);

        String text = "2. 业务功能需求\n"
                + "2.1.1 智能报告管理\n"
                + "3. 智能报告详情展示：在PC客户端展示完整报告信息。\n"
                + "2.1.2 终端盘点管理\n"
                + "盘点查询和导出。\n"
                + "3. 调整因子情况说明\n"
                + "不应进入。\n";

        String preview = (String) method.invoke(service, text);
        assertTrue(preview.contains("2.1.2 终端盘点管理"), preview);
        assertTrue(preview.contains("盘点查询和导出"));
        assertFalse(preview.contains("不应进入"));
    }
}
