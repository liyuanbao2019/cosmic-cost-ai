package com.li.jc.cosmiccostai.dto;

public class DocumentChapterItem {

    private String id;
    private String number;
    private String title;
    private int charCount;
    private boolean selected;
    private boolean recommended;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getCharCount() { return charCount; }
    public void setCharCount(int charCount) { this.charCount = charCount; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
    public boolean isRecommended() { return recommended; }
    public void setRecommended(boolean recommended) { this.recommended = recommended; }
}
