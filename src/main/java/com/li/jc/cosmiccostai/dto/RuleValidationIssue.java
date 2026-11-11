package com.li.jc.cosmiccostai.dto;

public class RuleValidationIssue {

    private int rowIndex;
    private String functionProcess;
    private String field;
    private String severity;
    private String message;
    private String suggestion;
    /** v2.8 检查项编号，如 V39；旧检查项为空。 */
    private String ruleCode;
    /** 本条问题涉及的 CFP（该行当前 CFP），用于按影响排序。 */
    private double cfpImpact;

    public int getRowIndex() {
        return rowIndex;
    }

    public void setRowIndex(int rowIndex) {
        this.rowIndex = rowIndex;
    }

    public String getFunctionProcess() {
        return functionProcess;
    }

    public void setFunctionProcess(String functionProcess) {
        this.functionProcess = functionProcess;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public double getCfpImpact() {
        return cfpImpact;
    }

    public void setCfpImpact(double cfpImpact) {
        this.cfpImpact = cfpImpact;
    }
}
