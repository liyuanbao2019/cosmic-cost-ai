package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.CosmicResult;
import com.li.jc.cosmiccostai.model.CosmicMovement;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CosmicAnalysisMovementRuleTest {

    private final CosmicAnalysisService service =
            new CosmicAnalysisService(new FileStorageService("target/cosmic-analysis-rule-storage"));

    @Test
    void createAndDeleteDoNotGainReadFromDescriptions() {
        CosmicResult created = analyze("用户新增", "查询重名用户后新增用户记录");
        CosmicResult deleted = analyze("用户删除", "读取用户详情后删除用户记录");

        assertMovements(created, 1, 0, 1, 1);
        assertMovements(deleted, 1, 0, 1, 1);
    }

    @Test
    void queryDoesNotGainWriteAndTemporaryExportIsEntryReadExit() {
        CosmicResult queried = analyze("用户查询", "保存查询条件并生成查询结果");
        CosmicResult exported = analyze("用户导出", "临时生成导出文件，下载后立即删除");

        assertMovements(queried, 1, 1, 0, 1);
        assertMovements(exported, 1, 1, 0, 1);
    }

    @Test
    void importAndDeliveryActionsUseAcceptedSampleMovements() {
        CosmicResult imported = analyze("用户导入", "上传并保存用户数据");
        CosmicResult notified = analyze("用户通知", "生成通知并发送给用户");
        CosmicResult recordedNotification = analyze("用户通知", "读取通知内容，发送给用户并保存发送记录");

        assertMovements(imported, 1, 0, 1, 1);
        assertMovements(notified, 1, 1, 0, 1);
        assertMovements(recordedNotification, 1, 1, 1, 1);
    }

    @Test
    void persistentExportAndScheduledProcessesFollowAcceptedVariants() {
        CosmicResult persistentExport = analyze("用户导出", "导出文件存储到服务器，下载后不删除");
        CosmicResult scheduledStatistics = analyze("定时统计订单数据", "读取订单并写入统计结果表");
        CosmicResult combinedCollection = analyze("定时文件采集", "下载文件并保存文件，同时写入业务数据表");

        assertMovements(persistentExport, 1, 1, 1, 1);
        assertMovements(scheduledStatistics, 1, 0, 1, 0);
        assertMovements(combinedCollection, 1, 0, 1, 0);
    }

    @Test
    void interfaceDirectionUsesTriggerContextFromDescription() {
        CosmicResult calledQuery = analyze("同步订单数据(查询数据类)", "外系统通过接口调用本系统查询订单");
        CosmicResult calledSync = analyze("同步订单数据(同步数据类)", "外系统通过接口调用本系统写入订单");
        CosmicResult callerPull = analyze("同步订单数据(查询数据类)", "定时调度调用外系统并将订单数据落库");
        CosmicResult callerPush = analyze("推送订单数据(同步数据类)", "定时调度读取订单并推送给外系统");

        assertMovements(calledQuery, 1, 1, 0, 1);
        assertMovements(calledSync, 1, 0, 1, 1);
        assertMovements(callerPull, 1, 0, 1, 0);
        assertMovements(callerPush, 1, 1, 0, 1);
    }

    @Test
    void detailedAiMovementsOverrideKeywordHeuristicsAndArePreserved() {
        ExtractedFunction function = new ExtractedFunction("常驻小区采集", "接收SEQ定界结果上报",
                "SEQ调用网优工作台接口上报常驻小区数据，系统落库后返回处理结果");
        function.setFunctionalUser("发起者：SEQ平台\n接收者：网优工作台");
        function.setTriggerEvent("SEQ平台调用定界结果上报接口");
        function.setMovements(Arrays.asList(
                movement("E", "接收SEQ定界结果上报"),
                movement("W", "保存常驻小区指标数据"),
                movement("X", "返回定界结果上报处理结果")));

        CosmicResult result = service.analyzeFunction(function);

        assertMovements(result, 1, 0, 1, 1);
        assertEquals("SEQ平台调用定界结果上报接口", result.getTriggerEvent());
        assertEquals(3, result.getMovements().size());
    }

    @Test
    void normalizesNoPayloadTriggerAndReusesKnownAttributesForSameDataGroup() {
        ExtractedFunction function = new ExtractedFunction("自动公告发布", "发送公告短信",
                "定时任务读取公告数据并发送短信");
        CosmicMovement trigger = movement("E", "接收定时任务触发信号", "定时触发信号", "待确认");
        CosmicMovement known = movement("R", "读取公告信息", "公告数据", "公告编号、六级地址");
        CosmicMovement reused = movement("X", "向短信平台发送公告信息", "公告数据", "待确认");
        CosmicMovement mixed = movement("X", "返回处理结果", "处理结果", "结果状态（待确认）");
        function.setMovements(Arrays.asList(trigger, known, reused, mixed));

        CosmicResult result = service.analyzeFunction(function);

        assertEquals("调度时间、调度任务名称", result.getMovements().get(0).getDataAttributes());
        assertEquals("公告编号、六级地址", result.getMovements().get(2).getDataAttributes());
        assertEquals("结果状态（待确认）", result.getMovements().get(3).getDataAttributes());
    }

    private CosmicResult analyze(String function, String description) {
        return service.analyzeFunction(new ExtractedFunction("用户管理", function, description));
    }

    private void assertMovements(CosmicResult result, int entry, int read, int write, int exit) {
        assertEquals(entry, result.getEntry());
        assertEquals(read, result.getRead());
        assertEquals(write, result.getWrite());
        assertEquals(exit, result.getExit());
        assertEquals(entry + read + write + exit, result.getCfp());
    }

    private CosmicMovement movement(String type, String description) {
        return movement(type, description, "测试数据组", "测试字段");
    }

    private CosmicMovement movement(String type, String description, String group, String attributes) {
        CosmicMovement movement = new CosmicMovement();
        movement.setType(type);
        movement.setDescription(description);
        movement.setDataGroup(group);
        movement.setDataAttributes(attributes);
        return movement;
    }
}
