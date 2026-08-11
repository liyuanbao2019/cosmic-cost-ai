package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.dto.RuleValidationIssue;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleValidationServiceTest {

    @Test
    void validatesRequiredKeywordAndProcessStructureRules() {
        RuleValidationService service = new RuleValidationService();

        FunctionSplitRow first = row("process-a", "E", "request", "id", "\u65b0\u589e");
        first.setSubProcessDescription("\u89e3\u6790\u7533\u8bf7\u4fe1\u606f");
        FunctionSplitRow second = row("process-a", "E", "", "status", "\u65b0\u589e");

        RuleValidationResponse response = service.validate(Arrays.asList(first, second));

        assertTrue(response.getIssueCount() >= 3);
        assertTrue(response.getErrorCount() >= 1);
        assertTrue(response.getRiskCount() >= 2);
    }

    @Test
    void completeProcessKeywordHitDoesNotCreateIssueWhenStructureIsComplete() {
        RuleValidationService service = new RuleValidationService();

        FunctionSplitRow entry = row("process-b", "E", "request", "id", "\u65b0\u589e");
        entry.setSubProcessDescription("\u63a5\u6536\u67e5\u8be2\u6761\u4ef6");
        FunctionSplitRow read = row("process-b", "R", "source", "amount", "\u65b0\u589e");
        read.setSubProcessDescription("\u8ba1\u7b97\u6c47\u603b\u6570\u636e");
        FunctionSplitRow exit = row("process-b", "X", "result", "total", "\u65b0\u589e");
        exit.setSubProcessDescription("\u8f93\u51fa\u6c47\u603b\u7ed3\u679c");

        RuleValidationResponse response = service.validate(Arrays.asList(entry, read, exit));

        assertEquals(0, response.getErrorCount());
        assertEquals(0, response.getRiskCount());
        assertEquals(0, response.getSuggestionCount());
        assertEquals(0, response.getExpressionConfirmCount());
        assertEquals(0, response.getIssueCount());
    }

    @Test
    void flagsReadInCreateOrDeleteAndWriteInQueryOrExport() {
        RuleValidationService service = new RuleValidationService();

        RuleValidationResponse createResponse = service.validate(Arrays.asList(
                row("用户新增", "E", "新增请求", "用户名称", "新增"),
                row("用户新增", "R", "用户数据", "用户编号", "新增"),
                row("用户新增", "W", "用户记录", "用户编号、用户名称", "新增")));
        RuleValidationResponse exportResponse = service.validate(Arrays.asList(
                row("用户导出", "E", "导出条件", "用户状态", "新增"),
                row("用户导出", "R", "用户数据", "用户编号、用户名称", "新增"),
                row("用户导出", "W", "导出文件", "文件名称", "新增"),
                row("用户导出", "X", "导出结果", "文件内容", "新增")));

        assertTrue(hasCombinationIssue(createResponse, "不应包含 R"));
        assertTrue(hasCombinationIssue(exportResponse, "缺少“文件在服务器持久保存”的明确依据"));
    }

    @Test
    void flagsMissingMovementsForKnownBusinessActions() {
        RuleValidationService service = new RuleValidationService();

        RuleValidationResponse queryResponse = service.validate(Arrays.asList(
                row("订单查询", "E", "查询条件", "订单编号", "新增"),
                row("订单查询", "X", "查询结果", "订单状态", "新增")));
        RuleValidationResponse updateResponse = service.validate(Arrays.asList(
                row("订单修改", "E", "修改请求", "订单编号", "新增"),
                row("订单修改", "X", "修改结果", "处理状态", "新增")));

        assertTrue(hasCombinationIssue(queryResponse, "当前缺少 R"));
        assertTrue(hasCombinationIssue(updateResponse, "当前缺少 W"));
        assertFalse(hasCombinationIssue(updateResponse, "当前缺少 R/W"));

        RuleValidationResponse directUpdate = service.validate(Arrays.asList(
                row("订单修改", "E", "修改请求", "订单编号", "新增"),
                row("订单修改", "W", "订单表", "订单状态", "新增"),
                row("订单修改", "X", "修改结果", "处理状态", "新增")));
        assertFalse(hasCombinationIssue(directUpdate, "当前缺少 R"), describe(directUpdate));
    }

    @Test
    void acceptsExpectedCreateAndQueryMovementCombinations() {
        RuleValidationService service = new RuleValidationService();

        RuleValidationResponse createResponse = service.validate(Arrays.asList(
                row("用户新增", "E", "新增请求", "用户名称", "新增"),
                row("用户新增", "W", "用户记录", "用户编号、用户名称", "新增"),
                row("用户新增", "X", "新增结果", "处理结果", "新增")));
        RuleValidationResponse queryResponse = service.validate(Arrays.asList(
                row("用户查询", "E", "查询条件", "用户编号", "新增"),
                row("用户查询", "R", "用户记录", "用户编号、用户名称", "新增"),
                row("用户查询", "X", "查询结果", "用户编号、用户名称", "新增")));

        assertEquals(0, createResponse.getIssueCount(), describe(createResponse));
        assertEquals(0, queryResponse.getIssueCount(), describe(queryResponse));
    }

    @Test
    void acceptsOutboundQueryRequestWithoutReadWhenEntryAlreadyCarriesParameters() {
        RuleValidationService service = new RuleValidationService();

        FunctionSplitRow entry = withTrigger(
                row("请求查询用户常驻小区信息", "E", "预处理工单触发数据",
                        "用户号码、工单号、故障时间", "新增"),
                "工单流转至预处理环节");
        entry.setSubProcessDescription("接收预处理环节触发的工单信息，包含用户号码、工单号、故障时间");
        FunctionSplitRow exit = withTrigger(
                row("请求查询用户常驻小区信息", "X", "查询请求参数",
                        "用户号码、工单号、故障时间", "新增"),
                "工单流转至预处理环节");
        exit.setSubProcessDescription("向华为SEQ平台发送用户常驻小区查询请求");

        RuleValidationResponse response = service.validate(Arrays.asList(entry, exit));

        assertEquals(0, response.getIssueCount());
    }

    @Test
    void acceptsImportPersistentExportScheduledAndMessageVariantsFromReference() {
        RuleValidationService service = new RuleValidationService();

        RuleValidationResponse importResponse = service.validate(Arrays.asList(
                row("用户导入", "E", "导入请求", "文件名称", "新增"),
                row("用户导入", "R", "Excel导入文件", "文件数据", "新增"),
                row("用户导入", "W", "用户数据表", "用户字段", "新增"),
                row("用户导入", "X", "导入结果", "校验结果", "新增")));

        FunctionSplitRow persistentWrite = row("用户导出", "W", "导出文件", "文件路径", "新增");
        persistentWrite.setSubProcessDescription("写入导出文件并存储到服务器");
        RuleValidationResponse exportResponse = service.validate(Arrays.asList(
                row("用户导出", "E", "导出请求", "导出条件", "新增"),
                row("用户导出", "R", "用户数据表", "用户字段", "新增"),
                persistentWrite,
                row("用户导出", "X", "Excel导出文件", "文件内容", "新增")));

        RuleValidationResponse statisticsResponse = service.validate(Arrays.asList(
                withTrigger(row("定时统计订单数据", "E", "统计任务", "任务时间", "新增"), "定时触发"),
                withTrigger(row("定时统计订单数据", "R", "订单数据表", "订单字段", "新增"), "定时触发"),
                withTrigger(row("定时统计订单数据", "W", "订单统计结果表", "统计字段", "新增"), "定时触发")));

        RuleValidationResponse messageResponse = service.validate(Arrays.asList(
                withTrigger(row("定时发送短信", "E", "短信任务", "任务时间", "新增"), "定时触发"),
                withTrigger(row("定时发送短信", "R", "业务数据表", "短信内容字段", "新增"), "定时触发"),
                withTrigger(row("定时发送短信", "W", "短信结果表", "发送状态", "新增"), "定时触发"),
                withTrigger(row("定时发送短信", "X", "短信", "短信内容", "新增"), "定时触发")));

        assertEquals(0, importResponse.getIssueCount());
        assertEquals(0, exportResponse.getIssueCount());
        assertEquals(0, statisticsResponse.getIssueCount());
        assertEquals(0, messageResponse.getIssueCount());
    }

    @Test
    void distinguishesInterfaceMovementsByTriggerAndDirection() {
        RuleValidationService service = new RuleValidationService();

        RuleValidationResponse calledQuery = service.validate(Arrays.asList(
                withTrigger(row("同步订单数据(查询数据类)", "E", "接口请求", "请求字段", "新增"), "接口调用触发"),
                withTrigger(row("同步订单数据(查询数据类)", "R", "订单数据表", "订单字段", "新增"), "接口调用触发"),
                withTrigger(row("同步订单数据(查询数据类)", "X", "接口响应", "响应字段", "新增"), "接口调用触发")));
        RuleValidationResponse calledSync = service.validate(Arrays.asList(
                withTrigger(row("同步订单数据(同步数据类)", "E", "接口请求", "请求字段", "新增"), "接口调用触发"),
                withTrigger(row("同步订单数据(同步数据类)", "W", "订单数据表", "订单字段", "新增"), "接口调用触发"),
                withTrigger(row("同步订单数据(同步数据类)", "X", "接口响应", "响应字段", "新增"), "接口调用触发")));
        RuleValidationResponse callerPull = service.validate(Arrays.asList(
                withTrigger(row("同步订单数据(查询数据类)", "E", "同步任务", "任务时间", "新增"), "时钟触发"),
                withTrigger(row("同步订单数据(查询数据类)", "W", "订单数据表", "订单字段", "新增"), "时钟触发"),
                withTrigger(row("同步订单数据(查询数据类)", "X", "同步结果", "处理状态", "新增"), "时钟触发")));
        RuleValidationResponse callerPush = service.validate(Arrays.asList(
                withTrigger(row("推送订单数据(同步数据类)", "E", "推送任务", "任务时间", "新增"), "时钟触发"),
                withTrigger(row("推送订单数据(同步数据类)", "R", "订单数据表", "订单字段", "新增"), "时钟触发"),
                withTrigger(row("推送订单数据(同步数据类)", "X", "推送报文", "订单字段", "新增"), "时钟触发")));

        assertEquals(0, calledQuery.getIssueCount(), describe(calledQuery));
        assertEquals(0, calledSync.getIssueCount(), describe(calledSync));
        assertEquals(0, callerPull.getIssueCount(), describe(callerPull));
        assertEquals(0, callerPush.getIssueCount(), describe(callerPush));
    }

    @Test
    void acceptsDocumentedAssociationWorkflowAndMultiWriteCollection() {
        RuleValidationService service = new RuleValidationService();

        FunctionSplitRow associatedRead = row("用户新增", "R", "用户角色表", "角色编号", "新增");
        associatedRead.setSubProcessDescription("读取用户关联角色");
        RuleValidationResponse associatedCreate = service.validate(Arrays.asList(
                row("用户新增", "E", "新增请求", "用户名称", "新增"),
                associatedRead,
                row("用户新增", "W", "用户数据表", "用户字段", "新增"),
                row("用户新增", "X", "新增结果", "处理状态", "新增")));

        RuleValidationResponse workflow = service.validate(Arrays.asList(
                row("派发流程工单", "E", "派发请求", "工单编号", "新增"),
                row("派发流程工单", "R", "流程环节处理人表", "处理人", "新增"),
                row("派发流程工单", "W", "流程主表", "流程状态", "新增"),
                row("派发流程工单", "X", "派发结果", "处理状态", "新增")));

        RuleValidationResponse collection = service.validate(Arrays.asList(
                withTrigger(row("定时文件采集", "E", "采集任务", "任务时间", "新增"), "定时触发"),
                withTrigger(row("定时文件采集", "W", "采集文件", "文件内容", "新增"), "定时触发"),
                withTrigger(row("定时文件采集", "W", "业务数据表", "业务字段", "新增"), "定时触发")));

        assertEquals(0, associatedCreate.getIssueCount(), describe(associatedCreate));
        assertEquals(0, workflow.getIssueCount(), describe(workflow));
        assertEquals(0, collection.getIssueCount(), describe(collection));
    }

    @Test
    void flagsTemplateGeneratedEntryWordingButAllowsSupportedSynchronousResponseEntry() {
        RuleValidationService service = new RuleValidationService();

        FunctionSplitRow firstEntry = row("向SEQ平台发送用户常驻小区查询请求", "E",
                "查询请求", "用户号码、工单号", "新增");
        firstEntry.setSubProcessDescription("接收向SEQ平台发送用户常驻小区查询请求请求");
        FunctionSplitRow firstExit = row("向SEQ平台发送用户常驻小区查询请求", "X",
                "SEQ查询请求", "用户号码、工单号", "新增");
        firstExit.setSubProcessDescription("发送SEQ常驻小区查询请求");
        FunctionSplitRow callbackEntry = row("向SEQ平台发送用户常驻小区查询请求", "E",
                "SEQ查询响应", "常驻小区信息", "新增");
        callbackEntry.setSubProcessDescription("接收SEQ常驻小区查询响应");
        FunctionSplitRow callbackWrite = row("向SEQ平台发送用户常驻小区查询请求", "W",
                "常驻小区结果", "常驻小区信息", "新增");

        RuleValidationResponse response = service.validate(
                Arrays.asList(firstEntry, firstExit, callbackEntry, callbackWrite));

        assertTrue(hasIssue(response, "请求请求"));
        assertTrue(hasIssue(response, "输入方向与系统向外输出方向矛盾"));
        assertFalse(hasIssue(response, "首行之后的 E 未能证明"), describe(response));
        assertFalse(hasIssue(response, "连续 E"), describe(response));
    }

    @Test
    void flagsConsecutiveEntryAndAsynchronousCallbackEmbeddedAfterExit() {
        RuleValidationService service = new RuleValidationService();
        RuleValidationResponse consecutive = service.validate(Arrays.asList(
                row("处理外部数据", "E", "用户请求", "业务编号", "新增"),
                row("处理外部数据", "E", "外部输入", "业务结果", "新增"),
                row("处理外部数据", "W", "业务结果表", "业务结果", "新增")));
        assertTrue(hasIssue(consecutive, "连续 E"), describe(consecutive));

        FunctionSplitRow request = row("查询外部处理结果", "X", "外部平台请求", "业务编号", "新增");
        request.setSubProcessDescription("向外部平台发送处理请求");
        FunctionSplitRow callback = row("查询外部处理结果", "E", "外部平台回调", "业务结果", "新增");
        callback.setSubProcessDescription("接收外部平台异步回调上报结果");
        RuleValidationResponse asynchronous = service.validate(Arrays.asList(
                row("查询外部处理结果", "E", "用户请求", "业务编号", "新增"),
                request,
                callback,
                row("查询外部处理结果", "W", "业务结果表", "业务结果", "新增")));
        assertTrue(hasIssue(asynchronous, "首行之后的 E 未能证明"), describe(asynchronous));
    }

    @Test
    void validatesCompoundActionsOrderCallbacksAndSharedProcessIdentity() {
        RuleValidationService service = new RuleValidationService();

        RuleValidationResponse scheduledPush = service.validate(Arrays.asList(
                withTrigger(row("定时统计告警并发送短信", "E", "定时任务", "无业务数据", "新增"), "定时触发"),
                withTrigger(row("定时统计告警并发送短信", "R", "告警数据表", "告警编号", "新增"), "定时触发"),
                withTrigger(row("定时统计告警并发送短信", "W", "告警统计表", "统计结果", "新增"), "定时触发")));
        assertTrue(hasCombinationIssue(scheduledPush, "当前缺少 X"), describe(scheduledPush));

        RuleValidationResponse compoundCrud = service.validate(Arrays.asList(
                row("新增并查询用户", "E", "用户请求", "用户名称", "新增"),
                row("新增并查询用户", "W", "用户表", "用户名称", "新增"),
                row("新增并查询用户", "X", "处理结果", "处理状态", "新增")));
        assertTrue(hasIssue(compoundCrud, "同时包含多个独立业务动作"), describe(compoundCrud));

        RuleValidationResponse wrongOrder = service.validate(Arrays.asList(
                row("修改订单", "E", "修改请求", "订单编号", "新增"),
                row("修改订单", "X", "修改结果", "处理状态", "新增"),
                row("修改订单", "R", "订单表", "订单编号", "新增"),
                row("修改订单", "W", "订单表", "订单状态", "新增")));
        assertTrue(hasIssue(wrongOrder, "X 输出后直接出现 R"), describe(wrongOrder));

        RuleValidationResponse callback = service.validate(Arrays.asList(
                withTrigger(row("接收告警处理结果回调", "E", "回调报文", "告警编号", "新增"), "外部接口回调"),
                withTrigger(row("接收告警处理结果回调", "W", "告警结果表", "处理状态", "新增"), "外部接口回调")));
        assertTrue(hasCombinationIssue(callback, "当前缺少 X"), describe(callback));

        FunctionSplitRow notificationWrite = withTrigger(
                row("定时检索公告发布失败并发送短信提醒", "W", "短信通知记录表", "发送状态", "新增"), "定时触发");
        notificationWrite.setSubProcessDescription("保存短信发送记录和状态");
        RuleValidationResponse scheduledNotification = service.validate(Arrays.asList(
                withTrigger(row("定时检索公告发布失败并发送短信提醒", "E", "定时任务", "无业务数据", "新增"), "定时触发"),
                withTrigger(row("定时检索公告发布失败并发送短信提醒", "R", "公告数据表", "公告状态", "新增"), "定时触发"),
                notificationWrite,
                withTrigger(row("定时检索公告发布失败并发送短信提醒", "X", "短信通知", "短信内容", "新增"), "定时触发")));
        assertFalse(hasIssue(scheduledNotification, "同时包含多个独立业务动作"), describe(scheduledNotification));
        assertFalse(hasCombinationIssue(scheduledNotification, "不应包含 W"), describe(scheduledNotification));
        assertEquals(0, scheduledNotification.getStructureRiskCount(), describe(scheduledNotification));

        FunctionSplitRow moduleOneEntry = row("处理订单", "E", "订单请求一", "订单编号一", "新增");
        FunctionSplitRow moduleOneWrite = row("处理订单", "W", "订单结果一", "结果编号一", "新增");
        FunctionSplitRow moduleTwoEntry = row("处理订单", "E", "订单请求二", "订单编号二", "新增");
        FunctionSplitRow moduleTwoWrite = row("处理订单", "W", "订单结果二", "结果编号二", "新增");
        moduleTwoEntry.setLevel1Module("module-b");
        moduleTwoWrite.setLevel1Module("module-b");
        RuleValidationResponse separateModules = service.validate(Arrays.asList(
                moduleOneEntry, moduleOneWrite, moduleTwoEntry, moduleTwoWrite));
        assertEquals(0, separateModules.getIssueCount(), describe(separateModules));
    }

    @Test
    void flagsInternalMovementRenderedAsCrossBoundaryOutput() {
        RuleValidationService service = new RuleValidationService();
        FunctionSplitRow entry = row("处理订单", "E", "订单请求", "订单编号", "新增");
        FunctionSplitRow write = row("处理订单", "W", "订单表", "订单状态", "新增");
        write.setSequenceDiagram("后台服务->UI");

        RuleValidationResponse response = service.validate(Arrays.asList(entry, write));

        assertTrue(hasIssue(response, "与跨边界输出 X 的方向相同"), describe(response));
        assertFalse(service.generatedBlockingIssues(Arrays.asList(entry, write)).isEmpty());
        assertThrows(com.li.jc.cosmiccostai.common.BusinessException.class,
                () -> service.ensureReadyForExport(Arrays.asList(entry, write), "导出"));
    }

    @Test
    void flagsFirstAndLastMovementButNotMoreThanFourRows() {
        RuleValidationService service = new RuleValidationService();

        RuleValidationResponse response = service.validate(Arrays.asList(
                row("处理订单数据", "R", "订单主表", "订单编号", "新增"),
                row("处理订单数据", "E", "处理请求", "请求编号", "新增"),
                row("处理订单数据", "R", "客户主表", "客户编号", "新增"),
                row("处理订单数据", "W", "订单结果表", "处理结果", "新增"),
                row("处理订单数据", "R", "状态字典表", "状态编码", "新增")));

        assertTrue(hasIssue(response, "第一个数据移动不是 E"), describe(response));
        assertTrue(hasIssue(response, "最后一个数据移动不是 W 或 X"), describe(response));
        assertTrue(hasIssueWithSeverity(response, "最后一个数据移动不是 W 或 X", "ERROR"), describe(response));
        assertFalse(hasIssue(response, "最多 4 行"), describe(response));
    }

    @Test
    void validatesReuseDegreeAndCfpMapping() {
        RuleValidationService service = new RuleValidationService();
        FunctionSplitRow entry = row("处理订单请求", "E", "处理请求", "订单编号", "新增");
        entry.setCfp(0.33D);
        FunctionSplitRow exit = row("处理订单请求", "X", "处理结果", "结果状态", "复用");
        exit.setCfp(1D);

        RuleValidationResponse response = service.validate(Arrays.asList(entry, exit));

        assertTrue(hasIssue(response, "新增 应为 1"), describe(response));
        assertTrue(hasIssue(response, "复用 应为 0.33"), describe(response));
        assertTrue(response.getErrorCount() >= 2, describe(response));
    }

    @Test
    void flagsFrontendGenericNameAndReferencedAttributesButNotAttributeCount() {
        RuleValidationService service = new RuleValidationService();
        FunctionSplitRow entry = row("翻页", "E", "翻页条件", "页码", "新增");
        entry.setDataAttributes("页码、每页数量");
        FunctionSplitRow exit = row("翻页", "X", "页面数据", "记录列表", "新增");
        exit.setDataAttributes("同上");

        RuleValidationResponse response = service.validate(Arrays.asList(entry, exit));

        assertTrue(hasIssue(response, "前端交互"), describe(response));
        assertFalse(hasIssue(response, "至少 6 个"), describe(response));
        assertTrue(hasIssue(response, "同上/见上"), describe(response));
    }

    @Test
    void treatsPendingAttributesAsMaterialRiskAndRejectsMixedPendingWording() {
        RuleValidationService service = new RuleValidationService();
        FunctionSplitRow trigger = row("推送公告信息", "E", "定时触发信号", "无业务数据", "新增");
        trigger.setTriggerEvent("定时任务触发");
        trigger.setSubProcessDescription("接收定时任务触发信号");
        trigger.setDataAttributes("无业务数据");
        FunctionSplitRow known = row("推送公告信息", "R", "公告数据", "公告编号、六级地址", "新增");
        known.setDataAttributes("公告编号、六级地址");
        FunctionSplitRow pendingSameGroup = row("推送公告信息", "X", "公告数据", "待确认", "新增");
        pendingSameGroup.setDataAttributes("待确认");
        FunctionSplitRow mixedPending = row("推送公告信息", "X", "处理结果", "结果状态（待确认）", "新增");
        mixedPending.setDataAttributes("结果状态（待确认）");

        RuleValidationResponse response = service.validate(
                Arrays.asList(trigger, known, pendingSameGroup, mixedPending));

        assertTrue(hasIssue(response, "相同数据组“公告数据”已有明确数据属性"), describe(response));
        assertTrue(hasIssue(response, "混用了“字段名（待确认）”表达"), describe(response));
        assertFalse(hasIssue(response, "无载荷的定时/时钟触发不应使用“待确认”"), describe(response));

        FunctionSplitRow request = row("提交工单处理", "E", "工单请求", "工单编号", "新增");
        request.setDataAttributes("工单编号");
        FunctionSplitRow pendingResult = row("提交工单处理", "X", "处理结果", "待确认", "新增");
        pendingResult.setDataAttributes("待确认");
        RuleValidationResponse genericResponse = service.validate(Arrays.asList(request, pendingResult));

        assertTrue(hasIssue(genericResponse, "送审材料尚有字段依据待补充"), describe(genericResponse));
    }

    @Test
    void flagsExactAndPartialThreeElementReuseInsideProcess() {
        RuleValidationService service = new RuleValidationService();
        FunctionSplitRow entry = row("处理客户资料", "E", "处理请求", "客户编号", "新增");
        FunctionSplitRow firstWrite = row("处理客户资料", "W", "客户资料", "客户编号", "新增");
        firstWrite.setSubProcessDescription("保存客户资料");
        firstWrite.setDataAttributes("客户编号、客户名称、客户类型、客户状态、创建时间、更新时间");
        FunctionSplitRow duplicateWrite = row("处理客户资料", "W", "客户资料", "客户编号", "新增");
        duplicateWrite.setSubProcessDescription("保存客户资料");
        duplicateWrite.setDataAttributes("更新时间、创建时间、客户状态、客户类型、客户名称、客户编号");
        FunctionSplitRow partialExit = row("处理客户资料", "X", "处理结果", "客户编号", "新增");
        partialExit.setSubProcessDescription("返回处理结果");

        RuleValidationResponse response = service.validate(
                Arrays.asList(entry, firstWrite, duplicateWrite, partialExit));

        assertTrue(hasIssue(response, "三要素与第 2 行完全相同"), describe(response));

        FunctionSplitRow readOne = row("查询客户资料", "R", "客户资料", "客户编号", "新增");
        readOne.setSubProcessDescription("读取客户资料");
        FunctionSplitRow readTwo = row("查询客户资料", "R", "客户资料", "客户编号", "新增");
        readTwo.setSubProcessDescription("获取客户资料");
        RuleValidationResponse partialResponse = service.validate(Arrays.asList(
                row("查询客户资料", "E", "查询条件", "客户编号", "新增"),
                readOne,
                readTwo,
                row("查询客户资料", "X", "查询结果", "客户编号", "新增")));

        assertTrue(hasIssue(partialResponse, "三要素有两项与第 2 行相同"), describe(partialResponse));
        RuleValidationIssue reuseCandidate = null;
        for (RuleValidationIssue issue : partialResponse.getIssues()) {
            if (issue.getMessage().contains("三要素有两项与第 2 行相同")) {
                reuseCandidate = issue;
                break;
            }
        }
        assertTrue(reuseCandidate != null && reuseCandidate.getSuggestion().contains("复用度 AI 调整"));
    }

    @Test
    void checksCrossFunctionReuseWithinSameBoundary() {
        RuleValidationService service = new RuleValidationService();
        FunctionSplitRow firstEntry = row("接收甲系统请求", "E", "接口请求", "请求编号", "新增");
        firstEntry.setFunctionalUser("同一功能用户");
        firstEntry.setSequenceDiagram("功能用户->本系统");
        FunctionSplitRow firstExit = row("接收甲系统请求", "X", "接口结果", "结果编码", "新增");
        firstExit.setFunctionalUser("同一功能用户");
        firstExit.setSequenceDiagram("本系统->功能用户");
        FunctionSplitRow secondEntry = row("接收乙系统请求", "E", "接口请求", "请求编号", "新增");
        secondEntry.setFunctionalUser("同一功能用户");
        secondEntry.setSequenceDiagram("功能用户->本系统");
        FunctionSplitRow secondExit = row("接收乙系统请求", "X", "接口结果", "结果编码", "新增");
        secondExit.setFunctionalUser("同一功能用户");
        secondExit.setSequenceDiagram("本系统->功能用户");

        RuleValidationResponse response = service.validate(
                Arrays.asList(firstEntry, firstExit, secondEntry, secondExit));

        assertTrue(hasIssue(response, "跨功能过程"), describe(response));
        assertTrue(hasIssue(response, "三要素完全相同"), describe(response));
    }

    @Test
    void flagsSeparateQueryAndDisplayForSameObject() {
        RuleValidationService service = new RuleValidationService();

        RuleValidationResponse response = service.validate(Arrays.asList(
                row("查询订单信息", "E", "查询条件", "订单编号", "新增"),
                row("查询订单信息", "R", "订单主表", "订单编号", "新增"),
                row("查询订单信息", "X", "订单列表", "订单编号", "新增"),
                row("展示订单信息", "E", "展示请求", "订单编号", "新增"),
                row("展示订单信息", "R", "订单主表", "订单编号", "新增"),
                row("展示订单信息", "X", "展示结果", "订单编号", "新增")));

        assertTrue(hasIssue(response, "分别以“查询”和“展示”拆分同一业务对象"), describe(response));
    }

    @Test
    void flagsDesignInstituteReviewLabelsUniversalTemplatesAndGenericAttributes() {
        RuleValidationService service = new RuleValidationService();
        FunctionSplitRow read = row("处理客户资料", "R", "客户资料表", "客户编号", "新增");
        read.setSubProcessDescription("读取客户资料相关数据");
        read.setDataAttributes("请求编号、操作人、请求时间");
        read.setEvidence("评审备注：OLD");

        RuleValidationResponse response = service.validate(Arrays.asList(
                row("处理客户资料", "E", "客户请求", "客户编号", "新增"),
                read,
                row("处理客户资料", "X", "客户结果", "客户编号", "新增")));

        assertTrue(hasIssue(response, "高频核减模板"), describe(response));
        assertTrue(hasIssue(response, "泛化占位组合"), describe(response));
        assertTrue(hasIssue(response, "评审标签“OLD”"), describe(response));
    }

    private boolean hasCombinationIssue(RuleValidationResponse response, String messagePart) {
        for (RuleValidationIssue issue : response.getIssues()) {
            if ("数据移动组合".equals(issue.getField()) && issue.getMessage().contains(messagePart)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasIssue(RuleValidationResponse response, String messagePart) {
        for (RuleValidationIssue issue : response.getIssues()) {
            if (issue.getMessage().contains(messagePart)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasIssueWithSeverity(RuleValidationResponse response, String messagePart, String severity) {
        for (RuleValidationIssue issue : response.getIssues()) {
            if (severity.equals(issue.getSeverity()) && issue.getMessage().contains(messagePart)) {
                return true;
            }
        }
        return false;
    }

    private String describe(RuleValidationResponse response) {
        StringBuilder builder = new StringBuilder();
        for (RuleValidationIssue issue : response.getIssues()) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(issue.getRowIndex()).append(':').append(issue.getField()).append(':').append(issue.getMessage());
        }
        return builder.toString();
    }

    private FunctionSplitRow row(String process, String movement, String dataGroup, String dataAttributes, String reuse) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setLevel1Module("module-a");
        row.setFunctionProcess(process);
        row.setSubProcessDescription("sub-process-" + movement);
        row.setMovementType(movement);
        row.setDataGroup(dataGroup);
        row.setDataAttributes(dataAttributes + "、审计字段二、审计字段三、审计字段四、审计字段五、审计字段六");
        row.setReuseDegree(reuse);
        row.setCfp("复用".equals(reuse) ? 0.33D : ("利旧".equals(reuse) ? 0D : 1D));
        return row;
    }

    private FunctionSplitRow withTrigger(FunctionSplitRow row, String triggerEvent) {
        row.setTriggerEvent(triggerEvent);
        return row;
    }
}
