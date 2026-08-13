package com.li.jc.cosmiccostai.dto;

public class ProjectListItem {

    private String projectId;
    private String projectName;
    private String splitLevel;
    private String batchId;
    private String sourceFileName;
    private String sourceType;
    private String status;
    private String createdAt;
    private String updatedAt;
    private int functionCount;
    private int issueCount;
    private int errorCount;
    private int riskCount;
    private int suggestionCount;
    private boolean excelExported;
    private boolean businessExported;
    private boolean wordExported;
    private String exportStatus;
    private boolean splitResultReady;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getSplitLevel() { return splitLevel; }

    public void setSplitLevel(String splitLevel) { this.splitLevel = splitLevel; }

    public String getBatchId() { return batchId; }

    public void setBatchId(String batchId) { this.batchId = batchId; }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getFunctionCount() {
        return functionCount;
    }

    public void setFunctionCount(int functionCount) {
        this.functionCount = functionCount;
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

    public boolean isExcelExported() {
        return excelExported;
    }

    public void setExcelExported(boolean excelExported) {
        this.excelExported = excelExported;
    }

    public boolean isBusinessExported() {
        return businessExported;
    }

    public void setBusinessExported(boolean businessExported) {
        this.businessExported = businessExported;
    }

    public boolean isWordExported() {
        return wordExported;
    }

    public void setWordExported(boolean wordExported) {
        this.wordExported = wordExported;
    }

    public String getExportStatus() {
        return exportStatus;
    }

    public void setExportStatus(String exportStatus) {
        this.exportStatus = exportStatus;
    }

    public boolean isSplitResultReady() { return splitResultReady; }

    public void setSplitResultReady(boolean splitResultReady) { this.splitResultReady = splitResultReady; }
}
