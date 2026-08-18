package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

/** Post-AI coverage of one top-level requirement chapter. */
public class RequirementChapterCoverage {

    private String headingPath;
    private String status;
    private String reason;
    private List<String> sourceReferences = new ArrayList<String>();
    private List<String> usedReferences = new ArrayList<String>();

    public String getHeadingPath() {
        return headingPath;
    }

    public void setHeadingPath(String headingPath) {
        this.headingPath = headingPath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<String> getSourceReferences() {
        return sourceReferences;
    }

    public void setSourceReferences(List<String> sourceReferences) {
        this.sourceReferences = sourceReferences;
    }

    public List<String> getUsedReferences() {
        return usedReferences;
    }

    public void setUsedReferences(List<String> usedReferences) {
        this.usedReferences = usedReferences;
    }
}
