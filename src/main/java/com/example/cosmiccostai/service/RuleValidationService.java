package com.example.cosmiccostai.service;

import com.example.cosmiccostai.dto.RuleValidationIssue;
import com.example.cosmiccostai.dto.RuleValidationResponse;
import com.example.cosmiccostai.model.FunctionSplitRow;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RuleValidationService {

    private static final List<String> HIGH_RISK_KEYWORDS = Arrays.asList(
            "\u5339\u914d", "\u89e3\u6790", "\u6062\u590d", "\u6e05\u9664", "\u8ba1\u7b97", "\u8fc7\u6ee4", "\u5237\u65b0",
            "\u8c03\u7814", "\u8bbe\u8ba1", "\u6d4b\u8bd5", "\u4e0a\u7ebf", "\u8c03\u4f18", "UI", "UE",
            "\u89c4\u5219\u6821\u9a8c", "\u8f93\u5165\u6821\u9a8c", "\u5408\u6cd5\u6027\u6821\u9a8c", "\u652f\u6491",
            "\u96c6\u6210", "\u73b0\u573a", "\u5b9e\u65bd", "\u63a5\u53e3", "\u914d\u7f6e", "\u9875\u9762\u8bbe\u8ba1",
            "\u8c03\u7528", "\u65e5\u5fd7", "\u89e6\u53d1", "\u63d0\u793a");

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
            validateKeywords(response, row, rowNumber);

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
        validateGroups(response, groups);
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

    private void validateKeywords(RuleValidationResponse response, FunctionSplitRow row, int rowNumber) {
        String text = safe(row.getFunctionProcess()) + " " + safe(row.getFunctionDescription()) + " "
                + safe(row.getSubProcessDescription()) + " " + safe(row.getDataGroup()) + " " + safe(row.getDataAttributes());
        for (String keyword : HIGH_RISK_KEYWORDS) {
            if (text.contains(keyword)) {
                add(response, rowNumber, safe(row.getFunctionProcess()), "\u8868\u8fbe\u98ce\u9669", "RISK",
                        "\u547d\u4e2d\u9ad8\u98ce\u9669\u8bcd: " + keyword,
                        "\u8bf7\u786e\u8ba4\u662f\u5426\u53ef\u4ee5\u6539\u6210\u660e\u786e\u7684\u4e1a\u52a1\u6570\u636e\u79fb\u52a8\u8868\u8fbe\uff0c\u4f8b\u5982\u63a5\u6536/\u8bfb\u53d6/\u5199\u5165/\u8f93\u51fa\u5177\u4f53\u6570\u636e\u3002");
                break;
            }
        }
    }

    private void validateGroups(RuleValidationResponse response, Map<String, List<RowRef>> groups) {
        for (Map.Entry<String, List<RowRef>> entry : groups.entrySet()) {
            String process = entry.getKey();
            if (process.startsWith("__ROW_")) {
                continue;
            }
            List<RowRef> rows = entry.getValue();
            if (rows.size() < 2) {
                add(response, rows.get(0).rowNumber, process, "\u529f\u80fd\u8fc7\u7a0b", "RISK",
                        "\u529f\u80fd\u8fc7\u7a0b\u53ea\u6709 1 \u4e2a\u5b50\u8fc7\u7a0b", "\u5efa\u8bae\u68c0\u67e5\u662f\u5426\u7f3a\u5c11\u8f93\u51fa\u6216\u5199\u5165\u7c7b\u6570\u636e\u79fb\u52a8\u3002");
            }
            String first = safe(rows.get(0).row.getMovementType()).toUpperCase(Locale.ROOT);
            String last = safe(rows.get(rows.size() - 1).row.getMovementType()).toUpperCase(Locale.ROOT);
            if (StringUtils.hasText(first) && !"E".equals(first)) {
                add(response, rows.get(0).rowNumber, process, "\u529f\u80fd\u8fc7\u7a0b", "RISK",
                        "\u529f\u80fd\u8fc7\u7a0b\u672a\u4ee5 E \u8f93\u5165\u5f00\u5934", "\u8bf7\u786e\u8ba4\u8be5\u529f\u80fd\u662f\u5426\u5b58\u5728\u89e6\u53d1\u8f93\u5165\u6570\u636e\u3002");
            }
            if (StringUtils.hasText(last) && !("W".equals(last) || "X".equals(last))) {
                add(response, rows.get(rows.size() - 1).rowNumber, process, "\u529f\u80fd\u8fc7\u7a0b", "RISK",
                        "\u529f\u80fd\u8fc7\u7a0b\u672a\u4ee5 W \u5199\u6216 X \u8f93\u51fa\u7ed3\u5c3e", "\u8bf7\u786e\u8ba4\u6700\u7ec8\u662f\u5426\u6709\u5199\u5165\u6216\u8f93\u51fa\u7ed3\u679c\u3002");
            }
            for (int i = 1; i < rows.size(); i++) {
                String prev = safe(rows.get(i - 1).row.getMovementType()).toUpperCase(Locale.ROOT);
                String current = safe(rows.get(i).row.getMovementType()).toUpperCase(Locale.ROOT);
                if ("E".equals(prev) && "E".equals(current)) {
                    add(response, rows.get(i).rowNumber, process, "\u6570\u636e\u79fb\u52a8\u7ed3\u6784", "RISK",
                            "\u540c\u4e00\u529f\u80fd\u8fc7\u7a0b\u5b58\u5728\u8fde\u7eed E \u8f93\u5165", "\u8bf7\u786e\u8ba4\u662f\u5426\u5e94\u62c6\u5206\u529f\u80fd\u8fc7\u7a0b\uff0c\u6216\u8865\u5145 R/W/X \u6570\u636e\u79fb\u52a8\u3002");
                    break;
                }
            }
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
        for (RuleValidationIssue issue : response.getIssues()) {
            if ("ERROR".equals(issue.getSeverity())) {
                errors++;
            } else if ("RISK".equals(issue.getSeverity())) {
                risks++;
            } else {
                suggestions++;
            }
        }
        response.setIssueCount(response.getIssues().size());
        response.setErrorCount(errors);
        response.setRiskCount(risks);
        response.setSuggestionCount(suggestions);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
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
