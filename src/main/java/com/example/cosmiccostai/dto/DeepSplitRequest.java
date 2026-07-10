package com.example.cosmiccostai.dto;

import com.example.cosmiccostai.model.FunctionSplitRow;

import java.util.List;

public class DeepSplitRequest {

    private List<FunctionSplitRow> rows;

    public List<FunctionSplitRow> getRows() {
        return rows;
    }

    public void setRows(List<FunctionSplitRow> rows) {
        this.rows = rows;
    }
}
