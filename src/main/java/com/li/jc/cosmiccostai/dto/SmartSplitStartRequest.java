package com.li.jc.cosmiccostai.dto;

public class SmartSplitStartRequest {

    private String sourceMode;
    private String prompt;
    private boolean reuseDegreeAdjustmentEnabled = true;

    public String getSourceMode() {
        return sourceMode;
    }

    public void setSourceMode(String sourceMode) {
        this.sourceMode = sourceMode;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public boolean isReuseDegreeAdjustmentEnabled() {
        return reuseDegreeAdjustmentEnabled;
    }

    public void setReuseDegreeAdjustmentEnabled(boolean reuseDegreeAdjustmentEnabled) {
        this.reuseDegreeAdjustmentEnabled = reuseDegreeAdjustmentEnabled;
    }
}
