package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class BatchSplitConfirmRequest {

    private List<String> chapterIds = new ArrayList<String>();
    private String aiInput;

    public List<String> getChapterIds() {
        return chapterIds;
    }

    public void setChapterIds(List<String> chapterIds) {
        this.chapterIds = chapterIds;
    }

    public String getAiInput() {
        return aiInput;
    }

    public void setAiInput(String aiInput) {
        this.aiInput = aiInput;
    }
}
