package com.li.jc.cosmiccostai.dto;

import com.li.jc.cosmiccostai.model.FunctionSplitRow;

import java.util.ArrayList;
import java.util.List;

public class ReuseDegreeAdjustmentRequest {

    private List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();

    public List<FunctionSplitRow> getRows() {
        return rows;
    }

    public void setRows(List<FunctionSplitRow> rows) {
        this.rows = rows;
    }
}
