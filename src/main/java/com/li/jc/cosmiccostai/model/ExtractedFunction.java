package com.li.jc.cosmiccostai.model;

import java.util.List;
import java.util.ArrayList;

public class ExtractedFunction {

    private String module;
    private String function;
    private String description;
    private String functionalUser;
    private String triggerEvent;
    private String sequenceDiagram;
    private List<CosmicMovement> movements;
    private String evidence;
    private List<String> sourceReferences = new ArrayList<String>();

    public ExtractedFunction() {
    }

    public ExtractedFunction(String module, String function, String description) {
        this.module = module;
        this.function = function;
        this.description = description;
    }

    public ExtractedFunction(String module, String function, String description, String sequenceDiagram) {
        this.module = module;
        this.function = function;
        this.description = description;
        this.sequenceDiagram = sequenceDiagram;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
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
}
