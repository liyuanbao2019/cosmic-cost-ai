package com.li.jc.cosmiccostai.model;

import java.util.ArrayList;
import java.util.List;

public class CosmicMovement {

    private String type;
    private String description;
    private String dataGroup;
    private String dataAttributes;
    private String sequenceDiagram;
    private String evidence;
    private List<String> sourceReferences = new ArrayList<String>();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
