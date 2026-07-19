package com.li.jc.cosmiccostai.dto;

import com.li.jc.cosmiccostai.model.FunctionSplitRow;

import java.util.ArrayList;
import java.util.List;

public class DeepSplitSuggestion {

    private String id;
    private String operation;
    private String title;
    private String reason;
    private String evidence;
    private List<String> sourceReferences = new ArrayList<String>();
    private double confidence;
    private double cfpBefore;
    private double cfpAfter;
    private List<Integer> targetRowNumbers = new ArrayList<Integer>();
    private List<FunctionSplitRow> replacementRows = new ArrayList<FunctionSplitRow>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public List<String> getSourceReferences() {
        return sourceReferences;
    }

    public void setSourceReferences(List<String> sourceReferences) {
        this.sourceReferences = sourceReferences;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
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

    public List<Integer> getTargetRowNumbers() {
        return targetRowNumbers;
    }

    public void setTargetRowNumbers(List<Integer> targetRowNumbers) {
        this.targetRowNumbers = targetRowNumbers;
    }

    public List<FunctionSplitRow> getReplacementRows() {
        return replacementRows;
    }

    public void setReplacementRows(List<FunctionSplitRow> replacementRows) {
        this.replacementRows = replacementRows;
    }
}
