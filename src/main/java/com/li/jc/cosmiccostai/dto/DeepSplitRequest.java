package com.li.jc.cosmiccostai.dto;

import com.li.jc.cosmiccostai.model.FunctionSplitRow;

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
