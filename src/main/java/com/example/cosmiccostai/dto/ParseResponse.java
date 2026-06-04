package com.example.cosmiccostai.dto;

public class ParseResponse {

    private String projectId;
    private String sourceFileName;
    private String documentTextPath;
    private int paragraphCount;
    private int tableCount;
    private int textLength;
    private String status;
    private String preview;
    private boolean requirementRecognized;
    private String requirementWarning;
    private String requirementSuggestion;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public String getDocumentTextPath() {
        return documentTextPath;
    }

    public void setDocumentTextPath(String documentTextPath) {
        this.documentTextPath = documentTextPath;
    }

    public int getParagraphCount() {
        return paragraphCount;
    }

    public void setParagraphCount(int paragraphCount) {
        this.paragraphCount = paragraphCount;
    }

    public int getTableCount() {
        return tableCount;
    }

    public void setTableCount(int tableCount) {
        this.tableCount = tableCount;
    }

    public int getTextLength() {
        return textLength;
    }

    public void setTextLength(int textLength) {
        this.textLength = textLength;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }

    public boolean isRequirementRecognized() {
        return requirementRecognized;
    }

    public void setRequirementRecognized(boolean requirementRecognized) {
        this.requirementRecognized = requirementRecognized;
    }

    public String getRequirementWarning() {
        return requirementWarning;
    }

    public void setRequirementWarning(String requirementWarning) {
        this.requirementWarning = requirementWarning;
    }

    public String getRequirementSuggestion() {
        return requirementSuggestion;
    }

    public void setRequirementSuggestion(String requirementSuggestion) {
        this.requirementSuggestion = requirementSuggestion;
    }
}
