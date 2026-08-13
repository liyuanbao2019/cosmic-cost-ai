package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

/** Adds requirement projects to an existing project-level aggregate. */
public class BatchSplitItemsRequest {

    private List<String> projectIds = new ArrayList<String>();
    private boolean reuseCompletedResults = true;

    public List<String> getProjectIds() {
        return projectIds;
    }

    public void setProjectIds(List<String> projectIds) {
        this.projectIds = projectIds;
    }

    public boolean isReuseCompletedResults() {
        return reuseCompletedResults;
    }

    public void setReuseCompletedResults(boolean reuseCompletedResults) {
        this.reuseCompletedResults = reuseCompletedResults;
    }
}
