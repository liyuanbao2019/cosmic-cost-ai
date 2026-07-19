package com.li.jc.cosmiccostai.dto;

public class RequirementCoverageModule {

    private String number;
    private String title;
    private int sourceLength;
    private int includedLength;
    private boolean included;
    private String status;

    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getSourceLength() { return sourceLength; }
    public void setSourceLength(int sourceLength) { this.sourceLength = sourceLength; }
    public int getIncludedLength() { return includedLength; }
    public void setIncludedLength(int includedLength) { this.includedLength = includedLength; }
    public boolean isIncluded() { return included; }
    public void setIncluded(boolean included) { this.included = included; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
