package com.example.cosmiccostai.dto;

public class ExcelExportResponse {

    private String projectId;
    private String excelOutputPath;
    private int functionCount;
    private int movementRowCount;
    private double totalCfp;
    private String status;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getExcelOutputPath() {
        return excelOutputPath;
    }

    public void setExcelOutputPath(String excelOutputPath) {
        this.excelOutputPath = excelOutputPath;
    }

    public int getFunctionCount() {
        return functionCount;
    }

    public void setFunctionCount(int functionCount) {
        this.functionCount = functionCount;
    }

    public int getMovementRowCount() {
        return movementRowCount;
    }

    public void setMovementRowCount(int movementRowCount) {
        this.movementRowCount = movementRowCount;
    }

    public double getTotalCfp() {
        return totalCfp;
    }

    public void setTotalCfp(double totalCfp) {
        this.totalCfp = totalCfp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
