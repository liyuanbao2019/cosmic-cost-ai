package com.li.jc.cosmiccostai.dto;

import com.li.jc.cosmiccostai.model.CosmicResult;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;

import java.util.List;

public class ReviewResponse {

    private String projectId;
    private int functionCount;
    private double totalCfp;
    private String status;
    private String sourceType;
    private String ruleVersion;
    private List<CosmicResult> results;
    private List<FunctionSplitRow> splitRows;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public int getFunctionCount() {
        return functionCount;
    }

    public void setFunctionCount(int functionCount) {
        this.functionCount = functionCount;
    }

    public double getTotalCfp() {
        return totalCfp;
    }

    public void setTotalCfp(double totalCfp) {
        this.totalCfp = totalCfp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public List<CosmicResult> getResults() {
        return results;
    }

    public void setResults(List<CosmicResult> results) {
        this.results = results;
    }

    public List<FunctionSplitRow> getSplitRows() {
        return splitRows;
    }

    public void setSplitRows(List<FunctionSplitRow> splitRows) {
        this.splitRows = splitRows;
    }
}
