package com.li.jc.cosmiccostai.dto;

public class AiPartitionProgressItem {

    private int partNumber;
    private String phase;
    private String label;
    private String status;
    private String startedAt;
    private String finishedAt;
    private String errorMessage;

    public AiPartitionProgressItem() {
    }

    public AiPartitionProgressItem(AiPartitionProgressItem source) {
        this.partNumber = source.partNumber;
        this.phase = source.phase;
        this.label = source.label;
        this.status = source.status;
        this.startedAt = source.startedAt;
        this.finishedAt = source.finishedAt;
        this.errorMessage = source.errorMessage;
    }

    public int getPartNumber() { return partNumber; }
    public void setPartNumber(int partNumber) { this.partNumber = partNumber; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
