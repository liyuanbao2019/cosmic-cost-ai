package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class ReuseDegreeAdjustmentDecision {

    private int rowIndex;
    private String originalReuseDegree;
    private String suggestedReuseDegree;
    private Double suggestedCfp;
    private String duplicateScope;
    private List<Integer> referenceRowIndexes = new ArrayList<Integer>();
    private List<String> matchedDimensions = new ArrayList<String>();
    private String boundaryDifference;
    private String reason;
    private boolean evidenceSufficient;
    private double confidence;
    private boolean applied;
    private String applyMessage;

    public int getRowIndex() {
        return rowIndex;
    }

    public void setRowIndex(int rowIndex) {
        this.rowIndex = rowIndex;
    }

    public String getOriginalReuseDegree() {
        return originalReuseDegree;
    }

    public void setOriginalReuseDegree(String originalReuseDegree) {
        this.originalReuseDegree = originalReuseDegree;
    }

    public String getSuggestedReuseDegree() {
        return suggestedReuseDegree;
    }

    public void setSuggestedReuseDegree(String suggestedReuseDegree) {
        this.suggestedReuseDegree = suggestedReuseDegree;
    }

    public Double getSuggestedCfp() {
        return suggestedCfp;
    }

    public void setSuggestedCfp(Double suggestedCfp) {
        this.suggestedCfp = suggestedCfp;
    }

    public String getDuplicateScope() {
        return duplicateScope;
    }

    public void setDuplicateScope(String duplicateScope) {
        this.duplicateScope = duplicateScope;
    }

    public List<Integer> getReferenceRowIndexes() {
        return referenceRowIndexes;
    }

    public void setReferenceRowIndexes(List<Integer> referenceRowIndexes) {
        this.referenceRowIndexes = referenceRowIndexes;
    }

    public List<String> getMatchedDimensions() {
        return matchedDimensions;
    }

    public void setMatchedDimensions(List<String> matchedDimensions) {
        this.matchedDimensions = matchedDimensions;
    }

    public String getBoundaryDifference() {
        return boundaryDifference;
    }

    public void setBoundaryDifference(String boundaryDifference) {
        this.boundaryDifference = boundaryDifference;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public boolean isEvidenceSufficient() {
        return evidenceSufficient;
    }

    public void setEvidenceSufficient(boolean evidenceSufficient) {
        this.evidenceSufficient = evidenceSufficient;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public boolean isApplied() {
        return applied;
    }

    public void setApplied(boolean applied) {
        this.applied = applied;
    }

    public String getApplyMessage() {
        return applyMessage;
    }

    public void setApplyMessage(String applyMessage) {
        this.applyMessage = applyMessage;
    }
}
