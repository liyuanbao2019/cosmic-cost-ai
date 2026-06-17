package com.example.cosmiccostai.dto;

public class AiReviewValidationItem {

    private int rowIndex;
    private String functionProcess;
    private String verdict;
    private String reason;
    private String suggestion;
    private String suggestedFunctionProcess;
    private String suggestedSubProcessDescription;
    private String suggestedDataGroup;
    private String suggestedDataAttributes;
    private double confidence;

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

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    public String getSuggestedFunctionProcess() {
        return suggestedFunctionProcess;
    }

    public void setSuggestedFunctionProcess(String suggestedFunctionProcess) {
        this.suggestedFunctionProcess = suggestedFunctionProcess;
    }

    public String getSuggestedSubProcessDescription() {
        return suggestedSubProcessDescription;
    }

    public void setSuggestedSubProcessDescription(String suggestedSubProcessDescription) {
        this.suggestedSubProcessDescription = suggestedSubProcessDescription;
    }

    public String getSuggestedDataGroup() {
        return suggestedDataGroup;
    }

    public void setSuggestedDataGroup(String suggestedDataGroup) {
        this.suggestedDataGroup = suggestedDataGroup;
    }

    public String getSuggestedDataAttributes() {
        return suggestedDataAttributes;
    }

    public void setSuggestedDataAttributes(String suggestedDataAttributes) {
        this.suggestedDataAttributes = suggestedDataAttributes;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }
}
