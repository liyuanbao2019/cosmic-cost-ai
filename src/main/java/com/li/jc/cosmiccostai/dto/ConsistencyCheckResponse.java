package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class ConsistencyCheckResponse {

    private String projectId;
    private boolean matched;
    private int sourceFunctionCount;
    private int excelFunctionCount;
    private int wordFunctionCount;
    private double sourceTotalCfp;
    private double excelTotalCfp;
    private double wordTotalCfp;
    private List<String> messages = new ArrayList<String>();

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public boolean isMatched() {
        return matched;
    }

    public void setMatched(boolean matched) {
        this.matched = matched;
    }

    public int getSourceFunctionCount() {
        return sourceFunctionCount;
    }

    public void setSourceFunctionCount(int sourceFunctionCount) {
        this.sourceFunctionCount = sourceFunctionCount;
    }

    public int getExcelFunctionCount() {
        return excelFunctionCount;
    }

    public void setExcelFunctionCount(int excelFunctionCount) {
        this.excelFunctionCount = excelFunctionCount;
    }

    public int getWordFunctionCount() {
        return wordFunctionCount;
    }

    public void setWordFunctionCount(int wordFunctionCount) {
        this.wordFunctionCount = wordFunctionCount;
    }

    public double getSourceTotalCfp() {
        return sourceTotalCfp;
    }

    public void setSourceTotalCfp(double sourceTotalCfp) {
        this.sourceTotalCfp = sourceTotalCfp;
    }

    public double getExcelTotalCfp() {
        return excelTotalCfp;
    }

    public void setExcelTotalCfp(double excelTotalCfp) {
        this.excelTotalCfp = excelTotalCfp;
    }

    public double getWordTotalCfp() {
        return wordTotalCfp;
    }

    public void setWordTotalCfp(double wordTotalCfp) {
        this.wordTotalCfp = wordTotalCfp;
    }

    public List<String> getMessages() {
        return messages;
    }

    public void setMessages(List<String> messages) {
        this.messages = messages;
    }

    public void addMessage(String message) {
        this.messages.add(message);
    }
}
