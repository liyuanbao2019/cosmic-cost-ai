package com.li.jc.cosmiccostai.dto;

/** 规则初审按检查项编号的汇总：命中行数与涉及 CFP。 */
public class RuleCheckSummary {

    private String ruleCode;
    private String title;
    private String category;
    private int rowCount;
    private double cfpImpact;

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public double getCfpImpact() {
        return cfpImpact;
    }

    public void setCfpImpact(double cfpImpact) {
        this.cfpImpact = cfpImpact;
    }
}
