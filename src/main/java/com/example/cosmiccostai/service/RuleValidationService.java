package com.example.cosmiccostai.service;

import com.example.cosmiccostai.dto.RuleValidationIssue;
import com.example.cosmiccostai.dto.RuleValidationResponse;
import com.example.cosmiccostai.model.FunctionSplitRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RuleValidationService {

    private static final List<String> HIGH_RISK_KEYWORDS = Arrays.asList(
            "\u5339\u914d", "\u89e3\u6790", "\u6062\u590d", "\u6e05\u9664", "\u8ba1\u7b97", "\u8fc7\u6ee4", "\u5237\u65b0",
            "\u8c03\u7814", "\u8bbe\u8ba1", "\u6d4b\u8bd5", "\u4e0a\u7ebf", "\u8c03\u4f18", "UI", "UE",
            "\u89c4\u5219\u6821\u9a8c", "\u8f93\u5165\u6821\u9a8c", "\u5408\u6cd5\u6027\u6821\u9a8c", "\u652f\u6491",
            "\u96c6\u6210", "\u73b0\u573a", "\u5b9e\u65bd", "\u63a5\u53e3", "\u914d\u7f6e", "\u9875\u9762\u8bbe\u8ba1",
            "\u8c03\u7528", "\u65e5\u5fd7", "\u89e6\u53d1", "\u63d0\u793a");

    private final String splitRulesPath;

    public RuleValidationService() {
        this("classpath:/static/split-check-rules.md");
    }

    public RuleValidationService(@Value("${cosmic.rules.split-rules-path:classpath:/static/split-check-rules.md}") String splitRulesPath) {
        this.splitRulesPath = splitRulesPath;
    }

    public RuleValidationResponse validate(List<FunctionSplitRow> rows) {
        RuleValidationResponse response = new RuleValidationResponse();
        response.setTotalRows(rows == null ? 0 : rows.size());
        if (rows == null || rows.isEmpty()) {
            add(response, 0, "", "\u5168\u8868", "ERROR", "\u6ca1\u6709\u53ef\u6821\u9a8c\u7684\u62c6\u5206\u6570\u636e", "\u8bf7\u5148\u4e0a\u4f20\u6216\u751f\u6210\u529f\u80fd\u70b9\u62c6\u5206\u8868\u3002");
            finish(response);
            return response;
        }

        Map<String, List<RowRef>> groups = new LinkedHashMap<String, List<RowRef>>();
        for (int i = 0; i < rows.size(); i++) {
            FunctionSplitRow row = rows.get(i);
            int rowNumber = i + 1;
            validateRequired(response, row, rowNumber);
            validateEnums(response, row, rowNumber);

            String key = safe(row.getFunctionProcess());
            if (!StringUtils.hasText(key)) {
                key = "__ROW_" + rowNumber;
            }
            List<RowRef> groupRows = groups.get(key);
            if (groupRows == null) {
                groupRows = new ArrayList<RowRef>();
                groups.put(key, groupRows);
            }
            groupRows.add(new RowRef(rowNumber, row));
        }
        Set<Integer> structureRiskRows = validateGroups(response, groups);
        List<String> highRiskKeywords = resolveHighRiskKeywords();
        for (int i = 0; i < rows.size(); i++) {
            validateKeywords(response, rows.get(i), i + 1, structureRiskRows.contains(i + 1), highRiskKeywords);
        }
        finish(response);
        return response;
    }

    private void validateRequired(RuleValidationResponse response, FunctionSplitRow row, int rowNumber) {
        require(response, row, rowNumber, row.getLevel1Module(), "B\u4e00\u7ea7\u6a21\u5757");
        require(response, row, rowNumber, row.getFunctionProcess(), "G\u529f\u80fd\u8fc7\u7a0b");
        require(response, row, rowNumber, row.getSubProcessDescription(), "H\u5b50\u8fc7\u7a0b\u63cf\u8ff0");
        require(response, row, rowNumber, row.getMovementType(), "I\u6570\u636e\u79fb\u52a8\u7c7b\u578b");
        require(response, row, rowNumber, row.getDataGroup(), "J\u6570\u636e\u7ec4");
        require(response, row, rowNumber, row.getDataAttributes(), "K\u6570\u636e\u5c5e\u6027");
        require(response, row, rowNumber, row.getReuseDegree(), "L\u590d\u7528\u5ea6");
    }

    private void require(RuleValidationResponse response, FunctionSplitRow row, int rowNumber, String value, String field) {
        if (!StringUtils.hasText(value)) {
            add(response, rowNumber, safe(row.getFunctionProcess()), field, "ERROR",
                    field + "\u5fc5\u586b", "\u8bf7\u8865\u5145\u8be5\u5b57\u6bb5\u540e\u518d\u5bfc\u51fa\u9001\u5ba1\u3002");
        }
    }

    private void validateEnums(RuleValidationResponse response, FunctionSplitRow row, int rowNumber) {
        String movement = safe(row.getMovementType()).toUpperCase(Locale.ROOT);
        if (StringUtils.hasText(movement) && !("E".equals(movement) || "R".equals(movement) || "W".equals(movement) || "X".equals(movement))) {
            add(response, rowNumber, safe(row.getFunctionProcess()), "I\u6570\u636e\u79fb\u52a8\u7c7b\u578b", "ERROR",
                    "\u6570\u636e\u79fb\u52a8\u7c7b\u578b\u5fc5\u987b\u662f E/R/W/X", "\u8bf7\u6309 COSMIC \u6570\u636e\u79fb\u52a8\u7c7b\u578b\u4fee\u6b63\u3002");
        }
        String reuse = safe(row.getReuseDegree());
        if (StringUtils.hasText(reuse) && !("\u65b0\u589e".equals(reuse) || "\u590d\u7528".equals(reuse) || "\u5229\u65e7".equals(reuse))) {
            add(response, rowNumber, safe(row.getFunctionProcess()), "L\u590d\u7528\u5ea6", "ERROR",
                    "\u590d\u7528\u5ea6\u5fc5\u987b\u662f\u65b0\u589e/\u590d\u7528/\u5229\u65e7", "\u8bf7\u4fee\u6b63\u590d\u7528\u5ea6\u679a\u4e3e\u503c\u3002");
        }
    }

    private void validateKeywords(RuleValidationResponse response, FunctionSplitRow row, int rowNumber,
                                  boolean hasStructureRisk, List<String> highRiskKeywords) {
        String text = safe(row.getFunctionProcess()) + " " + validationFunctionDescription(row.getFunctionDescription()) + " "
                + safe(row.getSubProcessDescription()) + " " + safe(row.getDataGroup()) + " " + safe(row.getDataAttributes());
        for (String keyword : highRiskKeywords) {
            if (text.contains(keyword)) {
                String severity = hasStructureRisk ? "RISK" : "SUGGESTION";
                String field = hasStructureRisk ? "\u8868\u8fbe\u98ce\u9669" : "\u8868\u8fbe\u786e\u8ba4";
                String keywordReason = keywordReason(keyword);
                String message = hasStructureRisk ? "\u8868\u8fbe\u4e2d\u51fa\u73b0\u201c" + keyword + "\u201d\uff1b" + keywordReason
                        + "\uff1b\u4e14\u8be5\u529f\u80fd\u8fc7\u7a0b\u5b58\u5728 E/R/W/X \u7ed3\u6784\u98ce\u9669\uff0c\u9700\u4f18\u5148\u4fee\u6b63\u3002"
                        : "\u8868\u8fbe\u4e2d\u51fa\u73b0\u201c" + keyword + "\u201d\uff1b" + keywordReason
                        + "\uff1b\u8bf7\u786e\u8ba4\u5b83\u662f\u5426\u5df2\u843d\u5230\u5177\u4f53\u4e1a\u52a1\u5bf9\u8c61\u3001\u6570\u636e\u7ec4\u548c\u8f93\u51fa\u7ed3\u679c\u3002";
                String suggestion = hasStructureRisk
                        ? "\u5148\u8865\u9f50\u8be5\u529f\u80fd\u8fc7\u7a0b\u7684 E \u8f93\u5165\u548c W/X \u5199\u5165\u6216\u8f93\u51fa\uff1b\u518d" + keywordRewriteAdvice(keyword)
                        : keywordRewriteAdvice(keyword);
                add(response, rowNumber, safe(row.getFunctionProcess()), field, severity, message, suggestion);
                break;
            }
        }
    }

    private String validationFunctionDescription(String value) {
        String text = safe(value);
        if (text.startsWith("\u672c\u529f\u80fd\u7528\u4e8e\u5b8c\u6210")) {
            text = text.replace("\uff0c\u652f\u6491\u8be5\u529f\u80fd\u7684\u8f93\u5165\u3001\u8bfb\u53d6\u3001\u5199\u5165\u6216\u8f93\u51fa\u7ed3\u679c\u3002", "");
        }
        return text;
    }

    private String keywordRewriteAdvice(String keyword) {
        if ("\u63d0\u793a".equals(keyword)) {
            return "\u5982\u679c\u786e\u5b9e\u662f\u65b0\u589e\u529f\u80fd\uff0c\u8bf7\u5199\u6210\u201c\u7cfb\u7edf\u8f93\u51faXX\u63d0\u793a\u7ed3\u679c/XX\u6821\u9a8c\u7ed3\u679c\u201d\uff0c\u5e76\u5728\u6570\u636e\u7ec4\u548c\u6570\u636e\u5c5e\u6027\u4e2d\u5199\u6e05\u63d0\u793a\u5bf9\u8c61\u3001\u63d0\u793a\u5185\u5bb9\u3001\u72b6\u6001\u6216\u539f\u56e0\uff1b\u5982\u679c\u53ea\u662f\u9875\u9762\u6587\u6848\u6539\u52a8\uff0c\u5efa\u8bae\u4e0d\u4f5c\u4e3a\u72ec\u7acb\u529f\u80fd\u70b9\u3002";
        }
        if ("\u65e5\u5fd7".equals(keyword)) {
            return "\u8bf7\u5224\u65ad\u662f\u5426\u6709\u65b0\u7684\u65e5\u5fd7\u67e5\u8be2\u3001\u751f\u6210\u6216\u8f93\u51fa\u9700\u6c42\uff1b\u5982\u679c\u6709\uff0c\u6539\u5199\u4e3a\u201c\u7528\u6237\u63d0\u4ea4XX\u67e5\u8be2\u6761\u4ef6/\u7cfb\u7edf\u8bfb\u53d6XX\u65e5\u5fd7/\u7cfb\u7edf\u8f93\u51faXX\u65e5\u5fd7\u660e\u7ec6\u201d\uff1b\u5982\u679c\u53ea\u662f\u540e\u53f0\u8bb0\u5f55\uff0c\u5efa\u8bae\u5220\u9664\u6216\u5408\u5e76\u5230\u76f8\u5173\u529f\u80fd\u3002";
        }
        if ("\u89e6\u53d1".equals(keyword) || "\u8c03\u7528".equals(keyword)) {
            return "\u8bf7\u4e0d\u8981\u53ea\u5199\u6280\u672f\u52a8\u4f5c\uff0c\u6539\u6210\u4e1a\u52a1\u7ed3\u679c\uff1a\u8c01\u53d1\u8d77\u8bf7\u6c42\u3001\u7cfb\u7edf\u8bfb\u53d6\u54ea\u4e9b\u4e1a\u52a1\u6570\u636e\u3001\u6700\u7ec8\u5199\u5165\u6216\u8f93\u51fa\u4ec0\u4e48\u7ed3\u679c\u3002";
        }
        if ("\u8ba1\u7b97".equals(keyword) || "\u5339\u914d".equals(keyword) || "\u8fc7\u6ee4".equals(keyword) || "\u89e3\u6790".equals(keyword)) {
            return "\u8bf7\u628a\u5904\u7406\u903b\u8f91\u8865\u6210\u6570\u636e\u79fb\u52a8\uff1a\u8f93\u5165\u4ec0\u4e48\u6761\u4ef6\u6216\u6570\u636e\u3001\u8bfb\u53d6\u4ec0\u4e48\u539f\u59cb\u6570\u636e\u3001\u751f\u6210/\u8f93\u51fa\u4ec0\u4e48\u4e1a\u52a1\u7ed3\u679c\uff0c\u6570\u636e\u5c5e\u6027\u8981\u5199\u5230\u5177\u4f53\u5b57\u6bb5\u3002";
        }
        if ("UI".equals(keyword) || "UE".equals(keyword) || "\u9875\u9762\u8bbe\u8ba1".equals(keyword) || "\u8bbe\u8ba1".equals(keyword)) {
            return "\u8bf7\u5224\u65ad\u662f\u5426\u53ea\u662f\u754c\u9762\u6837\u5f0f\u6216\u4ea4\u4e92\u8c03\u6574\uff1b\u5982\u679c\u4e0d\u6d89\u53ca\u65b0\u7684\u4e1a\u52a1\u6570\u636e\u8f93\u5165\u3001\u8bfb\u53d6\u3001\u5199\u5165\u6216\u8f93\u51fa\uff0c\u5efa\u8bae\u4e0d\u5355\u72ec\u8ba1\u91cf\uff1b\u5982\u679c\u6d89\u53ca\uff0c\u6539\u5199\u4e3a\u5bf9\u5e94\u4e1a\u52a1\u6570\u636e\u5904\u7406\u3002";
        }
        if ("\u63a5\u53e3".equals(keyword) || "\u914d\u7f6e".equals(keyword) || "\u96c6\u6210".equals(keyword)) {
            return "\u8bf7\u786e\u8ba4\u4e0d\u662f\u5355\u7eaf\u5229\u65e7\u6216\u6280\u672f\u63a5\u5165\uff1b\u5982\u679c\u6709\u65b0\u4e1a\u52a1\u5904\u7406\uff0c\u5199\u6e05\u63a5\u6536\u54ea\u4e9b\u5916\u90e8\u6570\u636e\u3001\u8bfb\u53d6/\u5199\u5165\u54ea\u4e9b\u4e1a\u52a1\u6570\u636e\u3001\u8f93\u51fa\u4ec0\u4e48\u5904\u7406\u7ed3\u679c\u3002";
        }
        return "\u8bf7\u5c06\u62bd\u8c61\u6216\u6280\u672f\u8868\u8fbe\u6539\u5199\u4e3a\u660e\u786e\u7684 E/R/W/X\uff1a\u8c01\u8f93\u5165\u3001\u7cfb\u7edf\u8bfb\u53d6\u4ec0\u4e48\u3001\u7cfb\u7edf\u5199\u5165\u6216\u8f93\u51fa\u4ec0\u4e48\u4e1a\u52a1\u7ed3\u679c\u3002";
    }

    private String keywordReason(String keyword) {
        if ("\u63d0\u793a".equals(keyword)) {
            return "\u201c\u63d0\u793a\u201d\u5e38\u53ef\u80fd\u53ea\u662f\u9875\u9762\u6587\u6848\u6216\u7cfb\u7edf\u53cd\u9988\uff0c\u4e0d\u4e00\u5b9a\u6784\u6210\u72ec\u7acb\u53ef\u8ba1\u91cf\u7684\u529f\u80fd";
        }
        if ("\u65e5\u5fd7".equals(keyword)) {
            return "\u201c\u65e5\u5fd7\u201d\u5bb9\u6613\u53ea\u662f\u6280\u672f\u8bb0\u5f55\u6216\u5df2\u6709\u67e5\u8be2\u80fd\u529b\uff0c\u9700\u786e\u8ba4\u662f\u5426\u6709\u65b0\u7684\u4e1a\u52a1\u8f93\u5165\u3001\u8bfb\u53d6\u6216\u8f93\u51fa";
        }
        if ("\u89e6\u53d1".equals(keyword) || "\u8c03\u7528".equals(keyword)) {
            return "\u8be5\u8bcd\u5bb9\u6613\u8868\u793a\u6280\u672f\u52a8\u4f5c\u6216\u5df2\u6709\u80fd\u529b\u8c03\u7528\uff0c\u9700\u786e\u8ba4\u662f\u5426\u4ea7\u751f\u65b0\u7684\u4e1a\u52a1\u6570\u636e\u79fb\u52a8";
        }
        if ("\u8ba1\u7b97".equals(keyword) || "\u5339\u914d".equals(keyword) || "\u8fc7\u6ee4".equals(keyword) || "\u89e3\u6790".equals(keyword)) {
            return "\u8be5\u8bcd\u504f\u5904\u7406\u903b\u8f91\uff0c\u9700\u786e\u8ba4\u5df2\u5199\u660e\u88ab\u5904\u7406\u7684\u4e1a\u52a1\u6570\u636e\u548c\u5904\u7406\u540e\u7684\u7ed3\u679c";
        }
        if ("UI".equals(keyword) || "UE".equals(keyword) || "\u9875\u9762\u8bbe\u8ba1".equals(keyword) || "\u8bbe\u8ba1".equals(keyword)) {
            return "\u8be5\u8bcd\u5bb9\u6613\u53ea\u4ee3\u8868\u754c\u9762\u6216\u4ea4\u4e92\u8c03\u6574\uff0c\u9700\u786e\u8ba4\u662f\u5426\u6d89\u53ca\u65b0\u7684\u4e1a\u52a1\u6570\u636e\u8f93\u5165\u6216\u8f93\u51fa";
        }
        if ("\u63a5\u53e3".equals(keyword) || "\u914d\u7f6e".equals(keyword) || "\u96c6\u6210".equals(keyword)) {
            return "\u8be5\u8bcd\u5bb9\u6613\u88ab\u8ba4\u4e3a\u5229\u65e7\u6216\u6280\u672f\u5b9e\u73b0\uff0c\u9700\u786e\u8ba4\u662f\u5426\u5bf9\u4e1a\u52a1\u6570\u636e\u4ea7\u751f\u65b0\u589e\u5904\u7406";
        }
        return "\u8be5\u8bcd\u504f\u62bd\u8c61\u6216\u6280\u672f\u8868\u8fbe\uff0c\u4e13\u5bb6\u8bc4\u5ba1\u65f6\u53ef\u80fd\u8981\u6c42\u6539\u5199\u4e3a\u660e\u786e\u7684\u6570\u636e\u79fb\u52a8";
    }

    private Set<Integer> validateGroups(RuleValidationResponse response, Map<String, List<RowRef>> groups) {
        Set<Integer> structureRiskRows = new HashSet<Integer>();
        for (Map.Entry<String, List<RowRef>> entry : groups.entrySet()) {
            String process = entry.getKey();
            if (process.startsWith("__ROW_")) {
                continue;
            }
            List<RowRef> rows = entry.getValue();
            if (rows.size() < 2) {
                add(response, rows.get(0).rowNumber, process, "\u529f\u80fd\u8fc7\u7a0b", "RISK",
                        "\u529f\u80fd\u8fc7\u7a0b\u53ea\u6709 1 \u4e2a\u5b50\u8fc7\u7a0b", "\u5efa\u8bae\u68c0\u67e5\u662f\u5426\u7f3a\u5c11\u8f93\u51fa\u6216\u5199\u5165\u7c7b\u6570\u636e\u79fb\u52a8\u3002");
                structureRiskRows.add(rows.get(0).rowNumber);
            }
            boolean hasEntry = false;
            boolean hasWriteOrExit = false;
            for (RowRef ref : rows) {
                String movement = safe(ref.row.getMovementType()).toUpperCase(Locale.ROOT);
                if ("E".equals(movement)) {
                    hasEntry = true;
                }
                if ("W".equals(movement) || "X".equals(movement)) {
                    hasWriteOrExit = true;
                }
            }
            if (!hasEntry) {
                addGroupIssue(response, rows, process, "\u529f\u80fd\u8fc7\u7a0b", "RISK",
                        "\u540c\u4e00\u529f\u80fd\u8fc7\u7a0b\u7ec4\u5185\u672a\u53d1\u73b0 E \u8f93\u5165",
                        "\u8bf7\u786e\u8ba4\u8be5\u529f\u80fd\u662f\u5426\u5b58\u5728\u89e6\u53d1\u8f93\u5165\u6570\u636e\u3002");
                markRows(structureRiskRows, rows);
            }
            if (!hasWriteOrExit) {
                addGroupIssue(response, rows, process, "\u529f\u80fd\u8fc7\u7a0b", "RISK",
                        "\u540c\u4e00\u529f\u80fd\u8fc7\u7a0b\u7ec4\u5185\u672a\u53d1\u73b0 W \u5199\u6216 X \u8f93\u51fa",
                        "\u8bf7\u786e\u8ba4\u6574\u4e2a\u529f\u80fd\u8fc7\u7a0b\u662f\u5426\u6709\u5199\u5165\u6216\u8f93\u51fa\u7ed3\u679c\u3002");
                markRows(structureRiskRows, rows);
            }
            for (int i = 1; i < rows.size(); i++) {
                String prev = safe(rows.get(i - 1).row.getMovementType()).toUpperCase(Locale.ROOT);
                String current = safe(rows.get(i).row.getMovementType()).toUpperCase(Locale.ROOT);
                if ("E".equals(prev) && "E".equals(current)) {
                    add(response, rows.get(i).rowNumber, process, "\u6570\u636e\u79fb\u52a8\u7ed3\u6784", "RISK",
                            "\u540c\u4e00\u529f\u80fd\u8fc7\u7a0b\u5b58\u5728\u8fde\u7eed E \u8f93\u5165", "\u8bf7\u786e\u8ba4\u662f\u5426\u5e94\u62c6\u5206\u529f\u80fd\u8fc7\u7a0b\uff0c\u6216\u8865\u5145 R/W/X \u6570\u636e\u79fb\u52a8\u3002");
                    structureRiskRows.add(rows.get(i).rowNumber);
                    break;
                }
            }
        }
        return structureRiskRows;
    }

    private void markRows(Set<Integer> rowNumbers, List<RowRef> rows) {
        for (RowRef ref : rows) {
            rowNumbers.add(ref.rowNumber);
        }
    }

    private void addGroupIssue(RuleValidationResponse response, List<RowRef> rows, String process, String field,
                               String severity, String message, String suggestion) {
        for (RowRef ref : rows) {
            add(response, ref.rowNumber, process, field, severity, message, suggestion);
        }
    }

    private void add(RuleValidationResponse response, int rowIndex, String functionProcess, String field,
                     String severity, String message, String suggestion) {
        RuleValidationIssue issue = new RuleValidationIssue();
        issue.setRowIndex(rowIndex);
        issue.setFunctionProcess(functionProcess);
        issue.setField(field);
        issue.setSeverity(severity);
        issue.setMessage(message);
        issue.setSuggestion(suggestion);
        response.getIssues().add(issue);
    }

    private void finish(RuleValidationResponse response) {
        int errors = 0;
        int risks = 0;
        int suggestions = 0;
        int structureRisks = 0;
        int expressionConfirms = 0;
        for (RuleValidationIssue issue : response.getIssues()) {
            if ("ERROR".equals(issue.getSeverity())) {
                errors++;
            } else if ("RISK".equals(issue.getSeverity())) {
                risks++;
                if ("\u529f\u80fd\u8fc7\u7a0b".equals(issue.getField()) || "\u6570\u636e\u79fb\u52a8\u7ed3\u6784".equals(issue.getField())) {
                    structureRisks++;
                }
            } else {
                suggestions++;
                if ("\u8868\u8fbe\u786e\u8ba4".equals(issue.getField())) {
                    expressionConfirms++;
                }
            }
        }
        response.setIssueCount(response.getIssues().size());
        response.setErrorCount(errors);
        response.setRiskCount(risks);
        response.setSuggestionCount(suggestions);
        response.setStructureRiskCount(structureRisks);
        response.setExpressionConfirmCount(expressionConfirms);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private List<String> resolveHighRiskKeywords() {
        List<String> keywords = parseKeywords(readSplitRules());
        return keywords.isEmpty() ? HIGH_RISK_KEYWORDS : keywords;
    }

    private List<String> parseKeywords(String rules) {
        List<String> keywords = new ArrayList<String>();
        if (!StringUtils.hasText(rules)) {
            return keywords;
        }
        boolean collecting = false;
        String[] lines = rules.split("\\R");
        for (String line : lines) {
            String value = line.trim();
            if (value.startsWith("## 2.") || value.startsWith("## 3.")) {
                collecting = true;
                continue;
            }
            if (collecting && value.startsWith("处理要求")) {
                collecting = false;
                continue;
            }
            if (collecting && value.startsWith("## ")) {
                collecting = false;
            }
            if (collecting && value.startsWith("- ")) {
                String keyword = value.substring(2).trim();
                if (StringUtils.hasText(keyword) && !keywords.contains(keyword)) {
                    keywords.add(keyword);
                }
            }
        }
        return keywords;
    }

    private String readSplitRules() {
        if (!StringUtils.hasText(splitRulesPath)) {
            return "";
        }
        if (splitRulesPath.startsWith("classpath:")) {
            String resourcePath = splitRulesPath.substring("classpath:".length());
            if (resourcePath.startsWith("/")) {
                resourcePath = resourcePath.substring(1);
            }
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                return "";
            }
            try (InputStream inputStream = resource.getInputStream()) {
                return readAll(inputStream);
            } catch (IOException ex) {
                return "";
            }
        }
        Path path = Paths.get(splitRulesPath);
        if (!Files.exists(path)) {
            Path projectPath = Paths.get("").toAbsolutePath().resolve(splitRulesPath);
            if (Files.exists(projectPath)) {
                path = projectPath;
            }
        }
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private String readAll(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        while ((read = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private static class RowRef {
        private final int rowNumber;
        private final FunctionSplitRow row;

        private RowRef(int rowNumber, FunctionSplitRow row) {
            this.rowNumber = rowNumber;
            this.row = row;
        }
    }
}
