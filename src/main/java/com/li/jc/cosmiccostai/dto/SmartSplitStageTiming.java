package com.li.jc.cosmiccostai.dto;

/** Persisted start/end timestamps for one user-visible smart-split stage. */
public class SmartSplitStageTiming {

    private String startedAt;
    private String completedAt;

    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }
}
