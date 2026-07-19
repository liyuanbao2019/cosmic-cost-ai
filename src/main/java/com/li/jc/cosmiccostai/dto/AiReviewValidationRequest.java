package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class AiReviewValidationRequest {

    private List<AiReviewValidationRow> rows = new ArrayList<AiReviewValidationRow>();

    public List<AiReviewValidationRow> getRows() {
        return rows;
    }

    public void setRows(List<AiReviewValidationRow> rows) {
        this.rows = rows;
    }
}
