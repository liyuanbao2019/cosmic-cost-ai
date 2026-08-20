package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.CosmicMovement;
import com.li.jc.cosmiccostai.model.CosmicResult;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FunctionSplitRowFactoryTest {

    @Test
    void usesAiMovementDetailsInsteadOfGenericRequestTemplates() {
        CosmicResult result = result("接收SEQ常驻小区定界结果上报");
        result.setFunctionalUser("发起者：SEQ平台\n接收者：网优工作台");
        result.setTriggerEvent("SEQ平台调用定界结果上报接口");
        result.setMovements(Arrays.asList(
                movement("E", "接收SEQ常驻小区定界结果上报", "SEQ定界结果",
                        "Eoms唯一标识、处理时间、查询结果、用户常驻信息"),
                movement("W", "保存解析后的常驻小区指标数据", "用户常驻小区结果记录",
                        "CGI、常驻小区名称、指标日期"),
                movement("X", "返回定界结果上报处理结果", "定界结果上报响应",
                        "ReturnCode、ReturnMessage")));

        List<FunctionSplitRow> rows = FunctionSplitRowFactory.create(metadata(), result);

        assertEquals(3, rows.size());
        assertEquals("接收SEQ常驻小区定界结果上报", rows.get(0).getSubProcessDescription());
        assertEquals("SEQ定界结果", rows.get(0).getDataGroup());
        assertEquals("ReturnCode、ReturnMessage", rows.get(2).getDataAttributes());
    }

    @Test
    void fallbackOutboundEntryAvoidsDirectionConflictAndInventedAttributes() {
        CosmicResult result = result("向SEQ平台发送用户常驻小区查询请求");
        result.setEntry(1);
        result.setRead(1);
        result.setExit(1);

        List<FunctionSplitRow> rows = FunctionSplitRowFactory.create(metadata(), result);

        assertEquals("发起用户常驻小区查询", rows.get(0).getSubProcessDescription());
        assertFalse(rows.get(0).getSubProcessDescription().contains("请求请求"));
        assertFalse(rows.get(0).getSubProcessDescription().startsWith("接收向"));
        assertEquals("待确认", rows.get(0).getDataAttributes());
        assertEquals("待确认", rows.get(1).getDataGroup());
    }

    @Test
    void normalizesExistingDetailedRowsBeforeReviewDisplay() {
        CosmicResult result = result("自动公告发布");
        result.setMovements(Arrays.asList(
                movement("E", "接收定时任务触发信号", "定时触发信号", "待确认"),
                movement("R", "读取公告数据", "公告数据", "公告编号、六级地址"),
                movement("X", "发送公告数据", "公告数据", "待确认")));

        List<FunctionSplitRow> rows = FunctionSplitRowFactory.create(metadata(), result);

        assertEquals("无业务数据", rows.get(0).getDataAttributes());
        assertEquals("公告编号、六级地址", rows.get(2).getDataAttributes());
    }

    @Test
    void humanizesDetailedUserEntryButKeepsItsBusinessMeaning() {
        CosmicResult result = result("申诉不符管理员视图数据查询");
        result.setFunctionalUser("发起者：管理员\n接收者：本系统");
        result.setMovements(Arrays.asList(
                movement("E", "接收申诉不符管理员视图数据查询请求，包含地市、工单类型等查询条件",
                        "申诉查询条件", "地市、工单类型"),
                movement("R", "读取申诉工单数据", "申诉工单", "工单编号、处理状态"),
                movement("X", "返回申诉工单查询结果", "申诉查询结果", "工单编号、处理状态")));

        List<FunctionSplitRow> rows = FunctionSplitRowFactory.create(metadata(), result);

        assertEquals("发起申诉不符管理员视图数据查询，提交地市、工单类型等查询条件",
                rows.get(0).getSubProcessDescription());
    }

    @Test
    void keepsOneFunctionHierarchyAcrossMovementsAndFillsMissingLeafWithProcess() {
        CosmicResult result = result("查询库存基础数据列表");
        result.setFunctionProcessId("FP-0001");
        result.setLevel1Module("月度基础数据自动运算规则");
        result.setLevel2Module("库存基础数据报表");
        CosmicMovement movement = movement("E", "发起库存查询", "查询条件", "账期");
        movement.setLevel1Module("七类基础数据报表");
        movement.setLevel2Module("库存基础数据报表");
        movement.setLevel3Module("");
        movement.setSourceHeadingPaths(Arrays.asList(
                "需求描述 > 七类基础数据报表 > 库存基础数据报表"));
        CosmicMovement exit = movement("X", "返回库存数据", "库存数据", "终端SN");
        exit.setLevel1Module("七类基础数据报表");
        exit.setLevel2Module("库存基础数据报表");
        exit.setLevel3Module("");
        exit.setSourceHeadingPaths(movement.getSourceHeadingPaths());
        result.setMovements(Arrays.asList(movement, exit));

        List<FunctionSplitRow> rows = FunctionSplitRowFactory.create(metadata(), result);

        assertEquals("FP-0001", rows.get(0).getFunctionProcessId());
        assertEquals("月度基础数据自动运算规则", rows.get(0).getLevel1Module());
        assertEquals("库存基础数据报表", rows.get(0).getLevel2Module());
        assertEquals("查询库存基础数据列表", rows.get(0).getLevel3Module());
        assertEquals(rows.get(0).getLevel1Module(), rows.get(1).getLevel1Module());
        assertEquals(rows.get(0).getLevel2Module(), rows.get(1).getLevel2Module());
        assertEquals(rows.get(0).getLevel3Module(), rows.get(1).getLevel3Module());
        assertEquals(movement.getSourceHeadingPaths(), rows.get(0).getSourceHeadingPaths());
    }

    private CosmicResult result(String function) {
        CosmicResult result = new CosmicResult();
        result.setModule("投诉用户常驻小区采集与分析");
        result.setFunction(function);
        result.setDescription("测试功能描述");
        return result;
    }

    private CosmicMovement movement(String type, String description, String group, String attributes) {
        CosmicMovement movement = new CosmicMovement();
        movement.setType(type);
        movement.setDescription(description);
        movement.setDataGroup(group);
        movement.setDataAttributes(attributes);
        return movement;
    }

    private ProjectMetadata metadata() {
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setName("测试项目");
        return metadata;
    }
}
