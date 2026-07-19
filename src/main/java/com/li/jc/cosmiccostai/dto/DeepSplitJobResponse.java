package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class DeepSplitJobResponse {

    private String jobId;
    private String projectId;
    private String status;
    private String model;
    private String sourceType;
    private boolean sourceAvailable;
    private int totalChunks;
    private int completedChunks;
    private int runningChunks;
    private int failedChunks;
    private int chunkParallelism;
    private int progressPercent;
    private String createdAt;
    private String updatedAt;
    private List<String> warnings = new ArrayList<String>();
    private List<DeepSplitSuggestion> suggestions = new ArrayList<DeepSplitSuggestion>();

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public boolean isSourceAvailable() {
        return sourceAvailable;
    }

    public void setSourceAvailable(boolean sourceAvailable) {
        this.sourceAvailable = sourceAvailable;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public int getCompletedChunks() {
        return completedChunks;
    }

    public void setCompletedChunks(int completedChunks) {
        this.completedChunks = completedChunks;
    }

    public int getRunningChunks() {
        return runningChunks;
    }

    public void setRunningChunks(int runningChunks) {
        this.runningChunks = runningChunks;
    }

    public int getFailedChunks() {
        return failedChunks;
    }

    public void setFailedChunks(int failedChunks) {
        this.failedChunks = failedChunks;
    }

    public int getChunkParallelism() {
        return chunkParallelism;
    }

    public void setChunkParallelism(int chunkParallelism) {
        this.chunkParallelism = chunkParallelism;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = progressPercent;
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

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public List<DeepSplitSuggestion> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<DeepSplitSuggestion> suggestions) {
        this.suggestions = suggestions;
    }
}
