package com.li.jc.cosmiccostai.dto;

public class WordExportResponse {

    private String projectId;
    private String wordOutputPath;
    private int functionCount;
    private double totalCfp;
    private String status;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getWordOutputPath() {
        return wordOutputPath;
    }

    public void setWordOutputPath(String wordOutputPath) {
        this.wordOutputPath = wordOutputPath;
    }

    public int getFunctionCount() {
        return functionCount;
    }

    public void setFunctionCount(int functionCount) {
        this.functionCount = functionCount;
    }

    public double getTotalCfp() {
        return totalCfp;
    }

    public void setTotalCfp(double totalCfp) {
        this.totalCfp = totalCfp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
