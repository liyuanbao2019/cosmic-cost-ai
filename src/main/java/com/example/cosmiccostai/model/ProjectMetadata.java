package com.example.cosmiccostai.model;

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
    private long fileSize;
    private String fileType;
    private String status;
    private String createdAt;
    private String updatedAt;

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
}
