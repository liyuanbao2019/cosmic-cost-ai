package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class BatchSplitJobResponse {

    private String batchId;
    private String splitLevel;
    private String projectGroupId;
    private String projectGroupName;
    private boolean reuseDegreeAdjustmentEnabled = true;
    private String status;
    private String createdAt;
    private String updatedAt;
    private String startedAt;
    private String finishedAt;
    private int total;
    private int parseQueued;
    private int preparing;
    private int awaitingConfirmation;
    private int confirmed;
    private int queued;
    private int running;
    private int completed;
    private int failed;
    private String projectMaterialStatus;
    private String projectMaterialMessage;
    private String projectMaterialCompletedAt;
    private double projectMaterialTotalCfp;
    private int projectMaterialRequirementCount;
    private int projectMaterialVersion;
    private List<ProjectMaterialFileResponse> projectMaterialFiles = new ArrayList<ProjectMaterialFileResponse>();
    private String requirementMaterialStatus;
    private String requirementMaterialMessage;
    private int requirementMaterialSuccessCount;
    private int requirementMaterialFailedCount;
    private ProjectMaterialFileResponse requirementMaterialArchive;
    private List<BatchSplitItemResponse> items = new ArrayList<BatchSplitItemResponse>();

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getSplitLevel() { return splitLevel; }
    public void setSplitLevel(String splitLevel) { this.splitLevel = splitLevel; }
    public String getProjectGroupId() { return projectGroupId; }
    public void setProjectGroupId(String projectGroupId) { this.projectGroupId = projectGroupId; }
    public String getProjectGroupName() { return projectGroupName; }
    public void setProjectGroupName(String projectGroupName) { this.projectGroupName = projectGroupName; }
    public boolean isReuseDegreeAdjustmentEnabled() { return reuseDegreeAdjustmentEnabled; }
    public void setReuseDegreeAdjustmentEnabled(boolean reuseDegreeAdjustmentEnabled) { this.reuseDegreeAdjustmentEnabled = reuseDegreeAdjustmentEnabled; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public int getParseQueued() { return parseQueued; }
    public void setParseQueued(int parseQueued) { this.parseQueued = parseQueued; }
    public int getPreparing() { return preparing; }
    public void setPreparing(int preparing) { this.preparing = preparing; }
    public int getAwaitingConfirmation() { return awaitingConfirmation; }
    public void setAwaitingConfirmation(int awaitingConfirmation) { this.awaitingConfirmation = awaitingConfirmation; }
    public int getConfirmed() { return confirmed; }
    public void setConfirmed(int confirmed) { this.confirmed = confirmed; }
    public int getQueued() { return queued; }
    public void setQueued(int queued) { this.queued = queued; }
    public int getRunning() { return running; }
    public void setRunning(int running) { this.running = running; }
    public int getCompleted() { return completed; }
    public void setCompleted(int completed) { this.completed = completed; }
    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }
    public String getProjectMaterialStatus() { return projectMaterialStatus; }
    public void setProjectMaterialStatus(String projectMaterialStatus) { this.projectMaterialStatus = projectMaterialStatus; }
    public String getProjectMaterialMessage() { return projectMaterialMessage; }
    public void setProjectMaterialMessage(String projectMaterialMessage) { this.projectMaterialMessage = projectMaterialMessage; }
    public String getProjectMaterialCompletedAt() { return projectMaterialCompletedAt; }
    public void setProjectMaterialCompletedAt(String projectMaterialCompletedAt) { this.projectMaterialCompletedAt = projectMaterialCompletedAt; }
    public double getProjectMaterialTotalCfp() { return projectMaterialTotalCfp; }
    public void setProjectMaterialTotalCfp(double projectMaterialTotalCfp) { this.projectMaterialTotalCfp = projectMaterialTotalCfp; }
    public int getProjectMaterialRequirementCount() { return projectMaterialRequirementCount; }
    public void setProjectMaterialRequirementCount(int projectMaterialRequirementCount) { this.projectMaterialRequirementCount = projectMaterialRequirementCount; }
    public int getProjectMaterialVersion() { return projectMaterialVersion; }
    public void setProjectMaterialVersion(int projectMaterialVersion) { this.projectMaterialVersion = projectMaterialVersion; }
    public List<ProjectMaterialFileResponse> getProjectMaterialFiles() { return projectMaterialFiles; }
    public void setProjectMaterialFiles(List<ProjectMaterialFileResponse> projectMaterialFiles) { this.projectMaterialFiles = projectMaterialFiles; }
    public String getRequirementMaterialStatus() { return requirementMaterialStatus; }
    public void setRequirementMaterialStatus(String requirementMaterialStatus) { this.requirementMaterialStatus = requirementMaterialStatus; }
    public String getRequirementMaterialMessage() { return requirementMaterialMessage; }
    public void setRequirementMaterialMessage(String requirementMaterialMessage) { this.requirementMaterialMessage = requirementMaterialMessage; }
    public int getRequirementMaterialSuccessCount() { return requirementMaterialSuccessCount; }
    public void setRequirementMaterialSuccessCount(int requirementMaterialSuccessCount) { this.requirementMaterialSuccessCount = requirementMaterialSuccessCount; }
    public int getRequirementMaterialFailedCount() { return requirementMaterialFailedCount; }
    public void setRequirementMaterialFailedCount(int requirementMaterialFailedCount) { this.requirementMaterialFailedCount = requirementMaterialFailedCount; }
    public ProjectMaterialFileResponse getRequirementMaterialArchive() { return requirementMaterialArchive; }
    public void setRequirementMaterialArchive(ProjectMaterialFileResponse requirementMaterialArchive) { this.requirementMaterialArchive = requirementMaterialArchive; }
    public List<BatchSplitItemResponse> getItems() { return items; }
    public void setItems(List<BatchSplitItemResponse> items) { this.items = items; }
}
