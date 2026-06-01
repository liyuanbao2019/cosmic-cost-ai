package com.example.cosmiccostai.model;

public class ExtractedFunction {

    private String module;
    private String function;
    private String description;

    public ExtractedFunction() {
    }

    public ExtractedFunction(String module, String function, String description) {
        this.module = module;
        this.function = function;
        this.description = description;
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
}
