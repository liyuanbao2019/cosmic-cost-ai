package com.example.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class RuleValidationResponse {

    private int totalRows;
    private int issueCount;
    private int errorCount;
    private int riskCount;
    private int suggestionCount;
    private int structureRiskCount;
    private int expressionConfirmCount;
    private List<RuleValidationIssue> issues = new ArrayList<RuleValidationIssue>();

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getIssueCount() {
        return issueCount;
    }

    public void setIssueCount(int issueCount) {
        this.issueCount = issueCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    public int getRiskCount() {
        return riskCount;
    }

    public void setRiskCount(int riskCount) {
        this.riskCount = riskCount;
    }

    public int getSuggestionCount() {
        return suggestionCount;
    }

    public void setSuggestionCount(int suggestionCount) {
        this.suggestionCount = suggestionCount;
    }

    public int getStructureRiskCount() {
        return structureRiskCount;
    }

    public void setStructureRiskCount(int structureRiskCount) {
        this.structureRiskCount = structureRiskCount;
    }

    public int getExpressionConfirmCount() {
        return expressionConfirmCount;
    }

    public void setExpressionConfirmCount(int expressionConfirmCount) {
        this.expressionConfirmCount = expressionConfirmCount;
    }

    public List<RuleValidationIssue> getIssues() {
        return issues;
    }

    public void setIssues(List<RuleValidationIssue> issues) {
        this.issues = issues;
    }
}
