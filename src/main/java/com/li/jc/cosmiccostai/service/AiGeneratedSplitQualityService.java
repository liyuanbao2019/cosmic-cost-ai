package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.RuleValidationIssue;
import com.li.jc.cosmiccostai.model.CosmicMovement;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class AiGeneratedSplitQualityService {

    private final RuleValidationService ruleValidationService;

    public AiGeneratedSplitQualityService(RuleValidationService ruleValidationService) {
        this.ruleValidationService = ruleValidationService;
    }

    public List<RuleValidationIssue> validate(List<ExtractedFunction> functions) {
        if (functions == null || functions.isEmpty()) {
            return Arrays.asList(issue("全表", "ERROR", "AI 未输出可校验的功能过程",
                    "请根据需求原文输出至少一个结构完整、首行 E、末行 W/X 的功能过程。"));
        }

        List<FunctionSplitRow> rows = toValidationRows(functions);
        return ruleValidationService.generatedBlockingIssues(rows);
    }

    public String summarize(List<RuleValidationIssue> issues) {
        StringBuilder builder = new StringBuilder();
        int count = Math.min(issues == null ? 0 : issues.size(), 5);
        for (int i = 0; i < count; i++) {
            if (builder.length() > 0) {
                builder.append("；");
            }
            RuleValidationIssue issue = issues.get(i);
            builder.append(text(issue.getFunctionProcess())).append("：").append(text(issue.getMessage()));
        }
        if (issues != null && issues.size() > count) {
            builder.append("；另有 ").append(issues.size() - count).append(" 项");
        }
        return builder.toString();
    }

    private List<FunctionSplitRow> toValidationRows(List<ExtractedFunction> functions) {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        for (ExtractedFunction function : functions) {
            if (function == null) {
                rows.add(toRow(null, null));
                continue;
            }
            MovementDataAttributeNormalizer.normalize(function.getMovements());
            if (function.getMovements() == null || function.getMovements().isEmpty()) {
                rows.add(toRow(function, null));
                continue;
            }
            for (CosmicMovement movement : function.getMovements()) {
                rows.add(toRow(function, movement));
            }
        }
        return rows;
    }

    private FunctionSplitRow toRow(ExtractedFunction function, CosmicMovement movement) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setProjectName("AI 生成结果结构校验");
        row.setLevel1Module(function == null ? "" : text(function.getModule()));
        row.setLevel2Module(row.getLevel1Module());
        row.setLevel3Module(row.getLevel1Module());
        row.setFunctionalUser(function == null ? "" : text(function.getFunctionalUser()));
        row.setTriggerEvent(function == null ? "" : text(function.getTriggerEvent()));
        row.setFunctionProcess(function == null ? "" : text(function.getFunction()));
        row.setFunctionDescription(function == null ? "" : text(function.getDescription()));
        row.setSubProcessDescription(movement == null ? "" : text(movement.getDescription()));
        row.setMovementType(movement == null ? "" : text(movement.getType()));
        row.setDataGroup(movement == null ? "" : text(movement.getDataGroup()));
        row.setDataAttributes(movement == null ? "" : text(movement.getDataAttributes()));
        row.setSequenceDiagram(movement == null ? "" : text(movement.getSequenceDiagram()));
        row.setReuseDegree("新增");
        row.setCfp(1D);
        return row;
    }

    private RuleValidationIssue issue(String field, String severity, String message, String suggestion) {
        RuleValidationIssue issue = new RuleValidationIssue();
        issue.setRowIndex(0);
        issue.setFunctionProcess("");
        issue.setField(field);
        issue.setSeverity(severity);
        issue.setMessage(message);
        issue.setSuggestion(suggestion);
        return issue;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
