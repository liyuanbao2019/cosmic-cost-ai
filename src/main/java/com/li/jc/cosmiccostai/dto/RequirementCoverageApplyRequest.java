package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class RequirementCoverageApplyRequest {

    private List<String> moduleNumbers = new ArrayList<>();

    public List<String> getModuleNumbers() { return moduleNumbers; }
    public void setModuleNumbers(List<String> moduleNumbers) { this.moduleNumbers = moduleNumbers; }
}
