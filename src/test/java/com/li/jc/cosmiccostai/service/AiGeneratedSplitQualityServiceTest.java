package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.RuleValidationIssue;
import com.li.jc.cosmiccostai.model.CosmicMovement;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiGeneratedSplitQualityServiceTest {

    private final AiGeneratedSplitQualityService service =
            new AiGeneratedSplitQualityService(new RuleValidationService());

    @Test
    void reportsAiGeneratedModifyProcessThatOmitsRequiredExitForInitialReview() {
        ExtractedFunction function = function("修改业务类型节点", "用户提交业务类型修改请求",
                movement("E", "提交业务类型修改请求", "修改请求", "业务类型编码"),
                movement("R", "读取原业务类型节点", "业务类型表", "业务类型编码、业务类型名称"),
                movement("W", "保存修改后的业务类型节点", "业务类型表", "业务类型编码、业务类型名称"));

        List<RuleValidationIssue> issues = service.validate(Arrays.asList(function));

        assertTrue(hasMessage(issues, "修改"), describe(issues));
        assertTrue(hasMessage(issues, "当前缺少 X"), describe(issues));
    }

    @Test
    void acceptsCompleteAiGeneratedModifyProcess() {
        ExtractedFunction function = function("修改业务类型节点", "用户提交业务类型修改请求",
                movement("E", "提交业务类型修改请求", "修改请求", "业务类型编码"),
                movement("R", "读取原业务类型节点", "业务类型表", "业务类型编码、业务类型名称"),
                movement("W", "保存修改后的业务类型节点", "业务类型表", "业务类型编码、业务类型名称"),
                movement("X", "返回业务类型修改结果", "修改结果", "结果状态、结果说明"));

        assertTrue(service.validate(Arrays.asList(function)).isEmpty());
    }

    @Test
    void acceptsUserExportAsEntryReadExitWithoutFileWrite() {
        ExtractedFunction function = function("导出业务明细", "用户发起业务明细导出请求",
                movement("E", "发起业务明细导出请求", "导出条件", "时间范围、业务类型"),
                movement("R", "读取业务明细", "业务明细表", "业务编号、业务状态"),
                movement("X", "输出Excel明细文件供用户下载", "Excel明细文件", "文件名称、文件内容"));

        assertTrue(service.validate(Arrays.asList(function)).isEmpty());
    }

    @Test
    void reportsAiGeneratedExportFileWriteAsTemplateMovement() {
        ExtractedFunction function = function("导出业务明细", "用户发起业务明细导出请求",
                movement("E", "发起业务明细导出请求", "导出条件", "时间范围、业务类型"),
                movement("R", "读取业务明细", "业务明细表", "业务编号、业务状态"),
                movement("W", "生成Excel明细文件", "Excel明细文件", "文件名称、文件内容"),
                movement("X", "输出Excel明细文件供用户下载", "Excel明细文件", "文件名称、文件内容"));

        List<RuleValidationIssue> issues = service.validate(Arrays.asList(function));

        assertTrue(hasMessage(issues, "不应包含 W"), describe(issues));
    }

    @Test
    void leavesEvidencePendingAttributesForInitialReviewInsteadOfBlockingGeneration() {
        ExtractedFunction function = function("查询业务类型节点", "用户查询业务类型节点",
                movement("E", "提交业务类型查询条件", "查询条件", "业务类型编码"),
                movement("R", "读取业务类型节点", "业务类型表", "待确认"),
                movement("X", "返回业务类型节点列表", "查询结果", "待确认"));

        List<RuleValidationIssue> issues = service.validate(Arrays.asList(function));

        assertFalse(hasMessage(issues, "当前缺少"), describe(issues));
        assertTrue(issues.isEmpty(), describe(issues));
    }

    private ExtractedFunction function(String name, String trigger, CosmicMovement... movements) {
        ExtractedFunction function = new ExtractedFunction();
        function.setModule("业务类型管理");
        function.setFunction(name);
        function.setDescription(name + "并形成明确业务结果");
        function.setFunctionalUser("业务用户");
        function.setTriggerEvent(trigger);
        function.setSequenceDiagram("业务用户->本系统;本系统->业务用户");
        function.setMovements(Arrays.asList(movements));
        return function;
    }

    private CosmicMovement movement(String type, String description, String group, String attributes) {
        CosmicMovement movement = new CosmicMovement();
        movement.setType(type);
        movement.setDescription(description);
        movement.setDataGroup(group);
        movement.setDataAttributes(attributes);
        movement.setSequenceDiagram("E".equals(type) ? "业务用户->本系统" : "本系统->业务用户");
        return movement;
    }

    private boolean hasMessage(List<RuleValidationIssue> issues, String part) {
        for (RuleValidationIssue issue : issues) {
            if (issue.getMessage() != null && issue.getMessage().contains(part)) {
                return true;
            }
        }
        return false;
    }

    private String describe(List<RuleValidationIssue> issues) {
        StringBuilder builder = new StringBuilder();
        for (RuleValidationIssue issue : issues) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(issue.getField()).append(':').append(issue.getMessage());
        }
        return builder.toString();
    }
}
