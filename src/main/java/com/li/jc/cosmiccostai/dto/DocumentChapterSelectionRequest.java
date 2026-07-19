package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class DocumentChapterSelectionRequest {

    private List<String> chapterIds = new ArrayList<String>();

    public List<String> getChapterIds() { return chapterIds; }
    public void setChapterIds(List<String> chapterIds) { this.chapterIds = chapterIds; }
}
