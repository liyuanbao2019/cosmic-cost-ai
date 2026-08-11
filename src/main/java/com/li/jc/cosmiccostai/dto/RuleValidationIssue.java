package com.li.jc.cosmiccostai.dto;

public class RuleValidationIssue {

    private int rowIndex;
    private String functionProcess;
    private String field;
    private String severity;
    private String message;
    private String suggestion;

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

}
