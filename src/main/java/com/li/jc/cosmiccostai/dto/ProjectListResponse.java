package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class ProjectListResponse {

    private int total;
    private List<ProjectListItem> projects = new ArrayList<ProjectListItem>();

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public List<ProjectListItem> getProjects() {
        return projects;
    }

    public void setProjects(List<ProjectListItem> projects) {
        this.projects = projects;
    }
}
