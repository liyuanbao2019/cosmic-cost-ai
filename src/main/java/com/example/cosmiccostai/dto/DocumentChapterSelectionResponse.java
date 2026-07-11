package com.example.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class DocumentChapterSelectionResponse {

    private int documentLength;
    private int selectedLength;
    private String aiInput;
    private List<DocumentChapterItem> chapters = new ArrayList<DocumentChapterItem>();

    public int getDocumentLength() { return documentLength; }
    public void setDocumentLength(int documentLength) { this.documentLength = documentLength; }
    public int getSelectedLength() { return selectedLength; }
    public void setSelectedLength(int selectedLength) { this.selectedLength = selectedLength; }
    public String getAiInput() { return aiInput; }
    public void setAiInput(String aiInput) { this.aiInput = aiInput; }
    public List<DocumentChapterItem> getChapters() { return chapters; }
    public void setChapters(List<DocumentChapterItem> chapters) { this.chapters = chapters; }
}
