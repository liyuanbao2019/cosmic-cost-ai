package com.li.jc.cosmiccostai.model;

public class ProjectMetadata {

    private String id;
    private String name;
    private String sourceFileName;
    private String storedFilePath;
    private String documentTextPath;
    private String requirementDetailPath;
    private String extractedFunctionsPath;
    private String cosmicResultsPath;
    private String functionSplitRowsPath;
    private String excelOutputPath;
    private String businessRequirementOutputPath;
    private String wordOutputPath;
    private String materialArchiveOutputPath;
    private long fileSize;
    private String fileType;
    private String status;
    private String createdAt;
    private String updatedAt;
    private String ruleVersion;
    private String splitLevel;
    private String batchId;
    private Integer functionCount;
    private Integer issueCount;
    private Integer errorCount;
    private Integer riskCount;
    private Integer suggestionCount;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public String getStoredFilePath() {
        return storedFilePath;
    }

    public void setStoredFilePath(String storedFilePath) {
        this.storedFilePath = storedFilePath;
    }

    public String getDocumentTextPath() {
        return documentTextPath;
    }

    public void setDocumentTextPath(String documentTextPath) {
        this.documentTextPath = documentTextPath;
    }

    public String getRequirementDetailPath() {
        return requirementDetailPath;
    }

    public void setRequirementDetailPath(String requirementDetailPath) {
        this.requirementDetailPath = requirementDetailPath;
    }

    public String getExtractedFunctionsPath() {
        return extractedFunctionsPath;
    }

    public void setExtractedFunctionsPath(String extractedFunctionsPath) {
        this.extractedFunctionsPath = extractedFunctionsPath;
    }

    public String getCosmicResultsPath() {
        return cosmicResultsPath;
    }

    public void setCosmicResultsPath(String cosmicResultsPath) {
        this.cosmicResultsPath = cosmicResultsPath;
    }

    public String getFunctionSplitRowsPath() {
        return functionSplitRowsPath;
    }

    public void setFunctionSplitRowsPath(String functionSplitRowsPath) {
        this.functionSplitRowsPath = functionSplitRowsPath;
    }

    public String getExcelOutputPath() {
        return excelOutputPath;
    }

    public void setExcelOutputPath(String excelOutputPath) {
        this.excelOutputPath = excelOutputPath;
    }

    public String getBusinessRequirementOutputPath() {
        return businessRequirementOutputPath;
    }

    public void setBusinessRequirementOutputPath(String businessRequirementOutputPath) {
        this.businessRequirementOutputPath = businessRequirementOutputPath;
    }

    public String getWordOutputPath() {
        return wordOutputPath;
    }

    public void setWordOutputPath(String wordOutputPath) {
        this.wordOutputPath = wordOutputPath;
    }

    public String getMaterialArchiveOutputPath() {
        return materialArchiveOutputPath;
    }

    public void setMaterialArchiveOutputPath(String materialArchiveOutputPath) {
        this.materialArchiveOutputPath = materialArchiveOutputPath;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
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

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public String getSplitLevel() { return splitLevel; }

    public void setSplitLevel(String splitLevel) { this.splitLevel = splitLevel; }

    public String getBatchId() { return batchId; }

    public void setBatchId(String batchId) { this.batchId = batchId; }

    public Integer getFunctionCount() { return functionCount; }

    public void setFunctionCount(Integer functionCount) { this.functionCount = functionCount; }

    public Integer getIssueCount() { return issueCount; }

    public void setIssueCount(Integer issueCount) { this.issueCount = issueCount; }

    public Integer getErrorCount() { return errorCount; }

    public void setErrorCount(Integer errorCount) { this.errorCount = errorCount; }

    public Integer getRiskCount() { return riskCount; }

    public void setRiskCount(Integer riskCount) { this.riskCount = riskCount; }

    public Integer getSuggestionCount() { return suggestionCount; }

    public void setSuggestionCount(Integer suggestionCount) { this.suggestionCount = suggestionCount; }
}
