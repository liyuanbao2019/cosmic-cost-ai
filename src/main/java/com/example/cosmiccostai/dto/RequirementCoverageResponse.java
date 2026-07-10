package com.example.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class RequirementCoverageResponse {

    private int sourceModuleCount;
    private int includedModuleCount;
    private int missingModuleCount;
    private int sourceLength;
    private int aiInputLength;
    private boolean readyForExtraction;
    private List<RequirementCoverageModule> modules = new ArrayList<>();

    public int getSourceModuleCount() { return sourceModuleCount; }
    public void setSourceModuleCount(int sourceModuleCount) { this.sourceModuleCount = sourceModuleCount; }
    public int getIncludedModuleCount() { return includedModuleCount; }
    public void setIncludedModuleCount(int includedModuleCount) { this.includedModuleCount = includedModuleCount; }
    public int getMissingModuleCount() { return missingModuleCount; }
    public void setMissingModuleCount(int missingModuleCount) { this.missingModuleCount = missingModuleCount; }
    public int getSourceLength() { return sourceLength; }
    public void setSourceLength(int sourceLength) { this.sourceLength = sourceLength; }
    public int getAiInputLength() { return aiInputLength; }
    public void setAiInputLength(int aiInputLength) { this.aiInputLength = aiInputLength; }
    public boolean isReadyForExtraction() { return readyForExtraction; }
    public void setReadyForExtraction(boolean readyForExtraction) { this.readyForExtraction = readyForExtraction; }
    public List<RequirementCoverageModule> getModules() { return modules; }
    public void setModules(List<RequirementCoverageModule> modules) { this.modules = modules; }
}
