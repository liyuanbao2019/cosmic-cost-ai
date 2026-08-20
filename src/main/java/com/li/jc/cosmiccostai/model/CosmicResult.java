package com.li.jc.cosmiccostai.model;

import java.util.List;
import java.util.ArrayList;

public class CosmicResult {

    private String functionProcessId;
    private String module;
    private String level1Module;
    private String level2Module;
    private String level3Module;
    private List<String> sourceHeadingPaths = new ArrayList<String>();
    private String function;
    private String description;
    private String functionalUser;
    private String triggerEvent;
    private String sequenceDiagram;
    private List<CosmicMovement> movements;
    private String evidence;
    private List<String> sourceReferences = new ArrayList<String>();
    private int entry;
    private int read;
    private int write;
    private int exit;
    private double cfp;

    public String getFunctionProcessId() {
        return functionProcessId;
    }

    public void setFunctionProcessId(String functionProcessId) {
        this.functionProcessId = functionProcessId;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
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

    public List<String> getSourceHeadingPaths() {
        return sourceHeadingPaths;
    }

    public void setSourceHeadingPaths(List<String> sourceHeadingPaths) {
        this.sourceHeadingPaths = sourceHeadingPaths;
    }

    public String getFunction() {
        return function;
    }

    public void setFunction(String function) {
        this.function = function;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public String getSequenceDiagram() {
        return sequenceDiagram;
    }

    public void setSequenceDiagram(String sequenceDiagram) {
        this.sequenceDiagram = sequenceDiagram;
    }

    public List<CosmicMovement> getMovements() {
        return movements;
    }

    public void setMovements(List<CosmicMovement> movements) {
        this.movements = movements;
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

    public int getEntry() {
        return entry;
    }

    public void setEntry(int entry) {
        this.entry = entry;
    }

    public int getRead() {
        return read;
    }

    public void setRead(int read) {
        this.read = read;
    }

    public int getWrite() {
        return write;
    }

    public void setWrite(int write) {
        this.write = write;
    }

    public int getExit() {
        return exit;
    }

    public void setExit(int exit) {
        this.exit = exit;
    }

    public double getCfp() {
        return cfp;
    }

    public void setCfp(double cfp) {
        this.cfp = cfp;
    }
}
