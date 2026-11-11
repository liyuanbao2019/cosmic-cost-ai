package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 事务三档法严口径预估（v2.8 第 3 节）：按过程定档（新 3 / 定 2⅓ / 改 1 / 0），
 * 再按骨架（首 E、主体、末 X）计量。这是按关键词与结构的确定性估算，
 * “什么算新对象”等需要通读需求的判断仍以人工为准。
 */
public class StrictEstimate {

    private double declaredCfp;
    private double strictCfp;
    private int processCount;
    /** 成立的唯一事务数（档位非 0 的过程数）。 */
    private int transactionCount;
    private int newCount;
    private int timerNewCount;
    private int modifyCount;
    private int zeroCount;
    /** 申报 CFP / 唯一事务数；大于 3.5 时申报结构必被压到事务数量级（V31）。 */
    private double declaredPerTransaction;
    /** 新建档（新 + 定）占成立事务的比例。 */
    private double newShare;
    private List<ProcessTier> processes = new ArrayList<ProcessTier>();

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

    public int getProcessCount() {
        return processCount;
    }

    public void setProcessCount(int processCount) {
        this.processCount = processCount;
    }

    public int getTransactionCount() {
        return transactionCount;
    }

    public void setTransactionCount(int transactionCount) {
        this.transactionCount = transactionCount;
    }

    public int getNewCount() {
        return newCount;
    }

    public void setNewCount(int newCount) {
        this.newCount = newCount;
    }

    public int getTimerNewCount() {
        return timerNewCount;
    }

    public void setTimerNewCount(int timerNewCount) {
        this.timerNewCount = timerNewCount;
    }

    public int getModifyCount() {
        return modifyCount;
    }

    public void setModifyCount(int modifyCount) {
        this.modifyCount = modifyCount;
    }

    public int getZeroCount() {
        return zeroCount;
    }

    public void setZeroCount(int zeroCount) {
        this.zeroCount = zeroCount;
    }

    public double getDeclaredPerTransaction() {
        return declaredPerTransaction;
    }

    public void setDeclaredPerTransaction(double declaredPerTransaction) {
        this.declaredPerTransaction = declaredPerTransaction;
    }

    public double getNewShare() {
        return newShare;
    }

    public void setNewShare(double newShare) {
        this.newShare = newShare;
    }

    public List<ProcessTier> getProcesses() {
        return processes;
    }

    public void setProcesses(List<ProcessTier> processes) {
        this.processes = processes;
    }
}
