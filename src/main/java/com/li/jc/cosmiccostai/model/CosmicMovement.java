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
    private String level1Module;
    private String level2Module;
    private String level3Module;
    private List<String> sourceHeadingPaths = new ArrayList<String>();

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
}
