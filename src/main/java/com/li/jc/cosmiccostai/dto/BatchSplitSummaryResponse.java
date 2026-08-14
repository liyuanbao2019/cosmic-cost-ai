package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class BatchSplitSummaryResponse {

    private String batchId;
    private String splitLevel;
    private String projectGroupId;
    private String projectGroupName;
    private int totalCount;
    private int completedCount;
    private int failedCount;
    private int statisticsAvailableCount;
    private int functionProcessCount;
    private int movementRowCount;
    private double totalCfp;
    private String projectMaterialStatus;
    private double projectMaterialTotalCfp;
    private List<BatchSplitSummaryItemResponse> items = new ArrayList<BatchSplitSummaryItemResponse>();

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getSplitLevel() { return splitLevel; }
    public void setSplitLevel(String splitLevel) { this.splitLevel = splitLevel; }
    public String getProjectGroupId() { return projectGroupId; }
    public void setProjectGroupId(String projectGroupId) { this.projectGroupId = projectGroupId; }
    public String getProjectGroupName() { return projectGroupName; }
    public void setProjectGroupName(String projectGroupName) { this.projectGroupName = projectGroupName; }
    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public int getCompletedCount() { return completedCount; }
    public void setCompletedCount(int completedCount) { this.completedCount = completedCount; }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
    public int getStatisticsAvailableCount() { return statisticsAvailableCount; }
    public void setStatisticsAvailableCount(int statisticsAvailableCount) { this.statisticsAvailableCount = statisticsAvailableCount; }
    public int getFunctionProcessCount() { return functionProcessCount; }
    public void setFunctionProcessCount(int functionProcessCount) { this.functionProcessCount = functionProcessCount; }
    public int getMovementRowCount() { return movementRowCount; }
    public void setMovementRowCount(int movementRowCount) { this.movementRowCount = movementRowCount; }
    public double getTotalCfp() { return totalCfp; }
    public void setTotalCfp(double totalCfp) { this.totalCfp = totalCfp; }
    public String getProjectMaterialStatus() { return projectMaterialStatus; }
    public void setProjectMaterialStatus(String projectMaterialStatus) { this.projectMaterialStatus = projectMaterialStatus; }
    public double getProjectMaterialTotalCfp() { return projectMaterialTotalCfp; }
    public void setProjectMaterialTotalCfp(double projectMaterialTotalCfp) { this.projectMaterialTotalCfp = projectMaterialTotalCfp; }
    public List<BatchSplitSummaryItemResponse> getItems() { return items; }
    public void setItems(List<BatchSplitSummaryItemResponse> items) { this.items = items; }
}
