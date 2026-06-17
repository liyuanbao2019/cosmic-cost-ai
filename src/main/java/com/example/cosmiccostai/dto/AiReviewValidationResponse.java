package com.example.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class AiReviewValidationResponse {

    private int requestedCount;
    private String model;
    private List<AiReviewValidationItem> items = new ArrayList<AiReviewValidationItem>();

    public int getRequestedCount() {
        return requestedCount;
    }

    public void setRequestedCount(int requestedCount) {
        this.requestedCount = requestedCount;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<AiReviewValidationItem> getItems() {
        return items;
    }

    public void setItems(List<AiReviewValidationItem> items) {
        this.items = items;
    }
}
