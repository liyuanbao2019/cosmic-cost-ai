package com.li.jc.cosmiccostai.dto;

import java.util.ArrayList;
import java.util.List;

public class AiModelSettings {
    private String selectedId;
    private String configPath;
    private List<AiModelProfile> models = new ArrayList<AiModelProfile>();

    public String getSelectedId() { return selectedId; }
    public void setSelectedId(String selectedId) { this.selectedId = selectedId; }
    public String getConfigPath() { return configPath; }
    public void setConfigPath(String configPath) { this.configPath = configPath; }
    public List<AiModelProfile> getModels() { return models; }
    public void setModels(List<AiModelProfile> models) { this.models = models; }
}
