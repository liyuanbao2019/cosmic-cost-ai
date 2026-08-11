package com.li.jc.cosmiccostai.dto;

import com.li.jc.cosmiccostai.model.FunctionSplitRow;

import java.util.ArrayList;
import java.util.List;

public class ReuseDegreeAdjustmentResponse {

    private String projectId;
    private String model;
    private String ruleVersion;
    private int totalRows;
    private int modelDecisionCount;
    private int adjustedRows;
    private int ruleAdjustedRows;
    private int skippedDecisions;
    private double cfpBefore;
    private double cfpAfter;
    private List<String> warnings = new ArrayList<String>();
    private List<ReuseDegreeAdjustmentDecision> decisions = new ArrayList<ReuseDegreeAdjustmentDecision>();
    private List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getModelDecisionCount() {
        return modelDecisionCount;
    }

    public void setModelDecisionCount(int modelDecisionCount) {
        this.modelDecisionCount = modelDecisionCount;
    }

    public int getAdjustedRows() {
        return adjustedRows;
    }

    public void setAdjustedRows(int adjustedRows) {
        this.adjustedRows = adjustedRows;
    }

    public int getRuleAdjustedRows() {
        return ruleAdjustedRows;
    }

    public void setRuleAdjustedRows(int ruleAdjustedRows) {
        this.ruleAdjustedRows = ruleAdjustedRows;
    }

    public int getSkippedDecisions() {
        return skippedDecisions;
    }

    public void setSkippedDecisions(int skippedDecisions) {
        this.skippedDecisions = skippedDecisions;
    }

    public double getCfpBefore() {
        return cfpBefore;
    }

    public void setCfpBefore(double cfpBefore) {
        this.cfpBefore = cfpBefore;
    }

    public double getCfpAfter() {
        return cfpAfter;
    }

    public void setCfpAfter(double cfpAfter) {
        this.cfpAfter = cfpAfter;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public List<ReuseDegreeAdjustmentDecision> getDecisions() {
        return decisions;
    }

    public void setDecisions(List<ReuseDegreeAdjustmentDecision> decisions) {
        this.decisions = decisions;
    }

    public List<FunctionSplitRow> getRows() {
        return rows;
    }

    public void setRows(List<FunctionSplitRow> rows) {
        this.rows = rows;
    }
}
