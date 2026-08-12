package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class ProjectListResponse {

    private int total;
    private int overallTotal;
    private int page = 1;
    private int pageSize = 20;
    private int totalPages = 1;
    private int totalWithRisks;
    private int totalExported;
    private List<String> statuses = new ArrayList<String>();
    private List<ProjectListItem> projects = new ArrayList<ProjectListItem>();

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getOverallTotal() { return overallTotal; }

    public void setOverallTotal(int overallTotal) { this.overallTotal = overallTotal; }

    public int getPage() { return page; }

    public void setPage(int page) { this.page = page; }

    public int getPageSize() { return pageSize; }

    public void setPageSize(int pageSize) { this.pageSize = pageSize; }

    public int getTotalPages() { return totalPages; }

    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public int getTotalWithRisks() { return totalWithRisks; }

    public void setTotalWithRisks(int totalWithRisks) { this.totalWithRisks = totalWithRisks; }

    public int getTotalExported() { return totalExported; }

    public void setTotalExported(int totalExported) { this.totalExported = totalExported; }

    public List<String> getStatuses() { return statuses; }

    public void setStatuses(List<String> statuses) { this.statuses = statuses; }

    public List<ProjectListItem> getProjects() {
        return projects;
    }

    public void setProjects(List<ProjectListItem> projects) {
        this.projects = projects;
    }
}
