package com.li.jc.cosmiccostai.dto;

import com.li.jc.cosmiccostai.model.CosmicResult;

import java.util.List;

public class CosmicAnalyzeResponse {

    private String projectId;
    private String cosmicResultsPath;
    private int functionCount;
    private double totalCfp;
    private String status;
    private List<CosmicResult> results;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getCosmicResultsPath() {
        return cosmicResultsPath;
    }

    public void setCosmicResultsPath(String cosmicResultsPath) {
        this.cosmicResultsPath = cosmicResultsPath;
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

    public List<CosmicResult> getResults() {
        return results;
    }

    public void setResults(List<CosmicResult> results) {
        this.results = results;
    }
}
