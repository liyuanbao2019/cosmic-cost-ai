package com.li.jc.cosmiccostai.dto;

import com.li.jc.cosmiccostai.model.ExtractedFunction;

import java.util.List;
import java.util.ArrayList;

public class ExtractResponse {

    private String projectId;
    private String requirementDetailPath;
    private String extractedFunctionsPath;
    private int functionCount;
    private String status;
    private List<ExtractedFunction> functions;
    private String chapterCoveragePath;
    private List<RequirementChapterCoverage> chapterCoverage = new ArrayList<RequirementChapterCoverage>();

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getRequirementDetailPath() {
        return requirementDetailPath;
    }

    public void setRequirementDetailPath(String requirementDetailPath) {
        this.requirementDetailPath = requirementDetailPath;
    }

    public String getExtractedFunctionsPath() {
        return extractedFunctionsPath;
    }

    public void setExtractedFunctionsPath(String extractedFunctionsPath) {
        this.extractedFunctionsPath = extractedFunctionsPath;
    }

    public int getFunctionCount() {
        return functionCount;
    }

    public void setFunctionCount(int functionCount) {
        this.functionCount = functionCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<ExtractedFunction> getFunctions() {
        return functions;
    }

    public void setFunctions(List<ExtractedFunction> functions) {
        this.functions = functions;
    }

    public String getChapterCoveragePath() {
        return chapterCoveragePath;
    }

    public void setChapterCoveragePath(String chapterCoveragePath) {
        this.chapterCoveragePath = chapterCoveragePath;
    }

    public List<RequirementChapterCoverage> getChapterCoverage() {
        return chapterCoverage;
    }

    public void setChapterCoverage(List<RequirementChapterCoverage> chapterCoverage) {
        this.chapterCoverage = chapterCoverage;
    }
}
