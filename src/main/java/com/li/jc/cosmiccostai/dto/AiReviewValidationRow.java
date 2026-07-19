package com.li.jc.cosmiccostai.dto;

import com.li.jc.cosmiccostai.model.FunctionSplitRow;

import java.util.ArrayList;
import java.util.List;

public class AiReviewValidationRow {

    private int rowIndex;
    private FunctionSplitRow row;
    private List<RuleValidationIssue> issues = new ArrayList<RuleValidationIssue>();
    private List<AiReviewValidationRow> contextRows = new ArrayList<AiReviewValidationRow>();

    public int getRowIndex() {
        return rowIndex;
    }

    public void setRowIndex(int rowIndex) {
        this.rowIndex = rowIndex;
    }

    public FunctionSplitRow getRow() {
        return row;
    }

    public void setRow(FunctionSplitRow row) {
        this.row = row;
    }

    public List<RuleValidationIssue> getIssues() {
        return issues;
    }

    public void setIssues(List<RuleValidationIssue> issues) {
        this.issues = issues;
    }

    public List<AiReviewValidationRow> getContextRows() {
        return contextRows;
    }

    public void setContextRows(List<AiReviewValidationRow> contextRows) {
        this.contextRows = contextRows;
    }
}
