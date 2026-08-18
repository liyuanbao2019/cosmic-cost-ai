package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class BatchSplitItemResponse {

    private String projectId;
    private String projectName;
    private String sourceFileName;
    private String status;
    private String stage;
    private int progressPercent;
    private String stageMessage;
    private boolean existingRows;
    private String requirementWarning;
    private List<String> selectedChapterIds = new ArrayList<String>();
    private String confirmedAt;
    private int inputLength;
    private String lastCompletedStage;
    private String errorMessage;
    private String startedAt;
    private String finishedAt;
    private int attempt;
    private int aiAttempt;
    private int aiMaxAttempts;
    private String aiAttemptStartedAt;
    private String aiAttemptDeadlineAt;
    private int issueCount;
    private int errorCount;
    private int riskCount;
    private int deepSuggestionCount;
    private int appliedSuggestionCount;
    private int skippedSuggestionCount;
    private boolean partial;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getSourceFileName() { return sourceFileName; }
    public void setSourceFileName(String sourceFileName) { this.sourceFileName = sourceFileName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int progressPercent) { this.progressPercent = progressPercent; }
    public String getStageMessage() { return stageMessage; }
    public void setStageMessage(String stageMessage) { this.stageMessage = stageMessage; }
    public boolean isExistingRows() { return existingRows; }
    public void setExistingRows(boolean existingRows) { this.existingRows = existingRows; }
    public String getRequirementWarning() { return requirementWarning; }
    public void setRequirementWarning(String requirementWarning) { this.requirementWarning = requirementWarning; }
    public List<String> getSelectedChapterIds() { return selectedChapterIds; }
    public void setSelectedChapterIds(List<String> selectedChapterIds) { this.selectedChapterIds = selectedChapterIds; }
    public String getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(String confirmedAt) { this.confirmedAt = confirmedAt; }
    public int getInputLength() { return inputLength; }
    public void setInputLength(int inputLength) { this.inputLength = inputLength; }
    public String getLastCompletedStage() { return lastCompletedStage; }
    public void setLastCompletedStage(String lastCompletedStage) { this.lastCompletedStage = lastCompletedStage; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public int getAiAttempt() { return aiAttempt; }
    public void setAiAttempt(int aiAttempt) { this.aiAttempt = aiAttempt; }
    public int getAiMaxAttempts() { return aiMaxAttempts; }
    public void setAiMaxAttempts(int aiMaxAttempts) { this.aiMaxAttempts = aiMaxAttempts; }
    public String getAiAttemptStartedAt() { return aiAttemptStartedAt; }
    public void setAiAttemptStartedAt(String aiAttemptStartedAt) { this.aiAttemptStartedAt = aiAttemptStartedAt; }
    public String getAiAttemptDeadlineAt() { return aiAttemptDeadlineAt; }
    public void setAiAttemptDeadlineAt(String aiAttemptDeadlineAt) { this.aiAttemptDeadlineAt = aiAttemptDeadlineAt; }
    public int getIssueCount() { return issueCount; }
    public void setIssueCount(int issueCount) { this.issueCount = issueCount; }
    public int getErrorCount() { return errorCount; }
    public void setErrorCount(int errorCount) { this.errorCount = errorCount; }
    public int getRiskCount() { return riskCount; }
    public void setRiskCount(int riskCount) { this.riskCount = riskCount; }
    public int getDeepSuggestionCount() { return deepSuggestionCount; }
    public void setDeepSuggestionCount(int deepSuggestionCount) { this.deepSuggestionCount = deepSuggestionCount; }
    public int getAppliedSuggestionCount() { return appliedSuggestionCount; }
    public void setAppliedSuggestionCount(int appliedSuggestionCount) { this.appliedSuggestionCount = appliedSuggestionCount; }
    public int getSkippedSuggestionCount() { return skippedSuggestionCount; }
    public void setSkippedSuggestionCount(int skippedSuggestionCount) { this.skippedSuggestionCount = skippedSuggestionCount; }
    public boolean isPartial() { return partial; }
    public void setPartial(boolean partial) { this.partial = partial; }
}
