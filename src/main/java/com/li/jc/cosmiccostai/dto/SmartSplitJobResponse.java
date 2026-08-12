package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class SmartSplitJobResponse {

    private String jobId;
    private String projectId;
    private String sourceMode;
    private String status;
    private String stage;
    private String stageMessage;
    private int progressPercent;
    private String deepSplitJobId;
    private String deepSplitStatus;
    private int deepSuggestionCount;
    private int appliedSuggestionCount;
    private int skippedSuggestionCount;
    private int totalRows;
    private double totalCfp;
    private int errorCount;
    private int riskCount;
    private int structureRiskCount;
    private String ruleVersion;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;
    private String completedAt;
    private List<String> appliedSuggestionIds = new ArrayList<String>();
    private List<String> warnings = new ArrayList<String>();

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getSourceMode() { return sourceMode; }
    public void setSourceMode(String sourceMode) { this.sourceMode = sourceMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getStageMessage() { return stageMessage; }
    public void setStageMessage(String stageMessage) { this.stageMessage = stageMessage; }
    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int progressPercent) { this.progressPercent = progressPercent; }
    public String getDeepSplitJobId() { return deepSplitJobId; }
    public void setDeepSplitJobId(String deepSplitJobId) { this.deepSplitJobId = deepSplitJobId; }
    public String getDeepSplitStatus() { return deepSplitStatus; }
    public void setDeepSplitStatus(String deepSplitStatus) { this.deepSplitStatus = deepSplitStatus; }
    public int getDeepSuggestionCount() { return deepSuggestionCount; }
    public void setDeepSuggestionCount(int deepSuggestionCount) { this.deepSuggestionCount = deepSuggestionCount; }
    public int getAppliedSuggestionCount() { return appliedSuggestionCount; }
    public void setAppliedSuggestionCount(int appliedSuggestionCount) { this.appliedSuggestionCount = appliedSuggestionCount; }
    public int getSkippedSuggestionCount() { return skippedSuggestionCount; }
    public void setSkippedSuggestionCount(int skippedSuggestionCount) { this.skippedSuggestionCount = skippedSuggestionCount; }
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    public double getTotalCfp() { return totalCfp; }
    public void setTotalCfp(double totalCfp) { this.totalCfp = totalCfp; }
    public int getErrorCount() { return errorCount; }
    public void setErrorCount(int errorCount) { this.errorCount = errorCount; }
    public int getRiskCount() { return riskCount; }
    public void setRiskCount(int riskCount) { this.riskCount = riskCount; }
    public int getStructureRiskCount() { return structureRiskCount; }
    public void setStructureRiskCount(int structureRiskCount) { this.structureRiskCount = structureRiskCount; }
    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }
    public List<String> getAppliedSuggestionIds() { return appliedSuggestionIds; }
    public void setAppliedSuggestionIds(List<String> appliedSuggestionIds) { this.appliedSuggestionIds = appliedSuggestionIds; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
}
