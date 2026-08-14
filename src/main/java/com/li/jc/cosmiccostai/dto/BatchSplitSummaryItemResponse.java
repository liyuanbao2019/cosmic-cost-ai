package com.li.jc.cosmiccostai.dto;

public class BatchSplitSummaryItemResponse {

    private String projectId;
    private String projectName;
    private String sourceFileName;
    private String status;
    private int functionProcessCount;
    private int movementRowCount;
    private double totalCfp;
    private boolean statisticsAvailable;
    private String message;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getSourceFileName() { return sourceFileName; }
    public void setSourceFileName(String sourceFileName) { this.sourceFileName = sourceFileName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getFunctionProcessCount() { return functionProcessCount; }
    public void setFunctionProcessCount(int functionProcessCount) { this.functionProcessCount = functionProcessCount; }
    public int getMovementRowCount() { return movementRowCount; }
    public void setMovementRowCount(int movementRowCount) { this.movementRowCount = movementRowCount; }
    public double getTotalCfp() { return totalCfp; }
    public void setTotalCfp(double totalCfp) { this.totalCfp = totalCfp; }
    public boolean isStatisticsAvailable() { return statisticsAvailable; }
    public void setStatisticsAvailable(boolean statisticsAvailable) { this.statisticsAvailable = statisticsAvailable; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
