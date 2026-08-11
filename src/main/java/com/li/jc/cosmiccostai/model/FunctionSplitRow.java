package com.li.jc.cosmiccostai.model;

import java.util.ArrayList;
import java.util.List;

public class FunctionSplitRow {

    private String projectName;
    private String level1Module;
    private String level2Module;
    private String level3Module;
    private String functionalUser;
    private String triggerEvent;
    private String functionProcess;
    private String functionDescription;
    private String subProcessDescription;
    private String movementType;
    private String dataGroup;
    private String dataAttributes;
    private String sequenceDiagram;
    private String reuseDegree;
    private double cfp;
    private String evidence;
    private List<String> sourceReferences = new ArrayList<String>();

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getLevel1Module() {
        return level1Module;
    }

    public void setLevel1Module(String level1Module) {
        this.level1Module = level1Module;
    }

    public String getLevel2Module() {
        return level2Module;
    }

    public void setLevel2Module(String level2Module) {
        this.level2Module = level2Module;
    }

    public String getLevel3Module() {
        return level3Module;
    }

    public void setLevel3Module(String level3Module) {
        this.level3Module = level3Module;
    }

    public String getFunctionalUser() {
        return functionalUser;
    }

    public void setFunctionalUser(String functionalUser) {
        this.functionalUser = functionalUser;
    }

    public String getTriggerEvent() {
        return triggerEvent;
    }

    public void setTriggerEvent(String triggerEvent) {
        this.triggerEvent = triggerEvent;
    }

    public String getFunctionProcess() {
        return functionProcess;
    }

    public void setFunctionProcess(String functionProcess) {
        this.functionProcess = functionProcess;
    }

    public String getFunctionDescription() {
        return functionDescription;
    }

    public void setFunctionDescription(String functionDescription) {
        this.functionDescription = functionDescription;
    }

    public String getSubProcessDescription() {
        return subProcessDescription;
    }

    public void setSubProcessDescription(String subProcessDescription) {
        this.subProcessDescription = subProcessDescription;
    }

    public String getMovementType() {
        return movementType;
    }

    public void setMovementType(String movementType) {
        this.movementType = movementType;
    }

    public String getDataGroup() {
        return dataGroup;
    }

    public void setDataGroup(String dataGroup) {
        this.dataGroup = dataGroup;
    }

    public String getDataAttributes() {
        return dataAttributes;
    }

    public void setDataAttributes(String dataAttributes) {
        this.dataAttributes = dataAttributes;
    }

    public String getSequenceDiagram() {
        return sequenceDiagram;
    }

    public void setSequenceDiagram(String sequenceDiagram) {
        this.sequenceDiagram = sequenceDiagram;
    }

    public String getReuseDegree() {
        return reuseDegree;
    }

    public void setReuseDegree(String reuseDegree) {
        this.reuseDegree = reuseDegree;
    }

    public double getCfp() {
        return cfp;
    }

    public void setCfp(double cfp) {
        this.cfp = cfp;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public List<String> getSourceReferences() {
        return sourceReferences;
    }

    public void setSourceReferences(List<String> sourceReferences) {
        this.sourceReferences = sourceReferences;
    }
}
