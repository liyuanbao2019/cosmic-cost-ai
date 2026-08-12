package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class BatchSplitCreateRequest {

    private List<String> projectIds = new ArrayList<String>();
    private String splitLevel = "REQUIREMENT";

    public List<String> getProjectIds() {
        return projectIds;
    }

    public void setProjectIds(List<String> projectIds) {
        this.projectIds = projectIds;
    }

    public String getSplitLevel() {
        return splitLevel;
    }

    public void setSplitLevel(String splitLevel) {
        this.splitLevel = splitLevel;
    }
}
