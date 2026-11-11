package com.li.jc.cosmiccostai.dto;

/** 单个功能过程的事务三档判定（v2.8 第 3 节）。 */
public class ProcessTier {

    /** 过程首行行号（1 起）。 */
    private int rowIndex;
    private String functionProcess;
    private String level3Module;
    /** 新 / 定 / 改 / 0。 */
    private String tier;
    private String reason;
    private int rowCount;
    private double declaredCfp;
    private double strictCfp;

    public int getRowIndex() {
        return rowIndex;
    }

    public void setRowIndex(int rowIndex) {
        this.rowIndex = rowIndex;
    }

    public String getFunctionProcess() {
        return functionProcess;
    }

    public void setFunctionProcess(String functionProcess) {
        this.functionProcess = functionProcess;
    }

    public String getLevel3Module() {
        return level3Module;
    }

    public void setLevel3Module(String level3Module) {
        this.level3Module = level3Module;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public double getDeclaredCfp() {
        return declaredCfp;
    }

    public void setDeclaredCfp(double declaredCfp) {
        this.declaredCfp = declaredCfp;
    }

    public double getStrictCfp() {
        return strictCfp;
    }

    public void setStrictCfp(double strictCfp) {
        this.strictCfp = strictCfp;
    }
}
