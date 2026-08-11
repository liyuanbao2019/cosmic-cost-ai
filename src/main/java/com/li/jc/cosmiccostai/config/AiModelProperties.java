package com.li.jc.cosmiccostai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "cosmic.ai")
public class AiModelProperties {

    private String selectedId = "default";
    private String provider = "openai-compatible";
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "gpt-4o-mini";
    private long timeoutSeconds = 360;
    private int maxRetries = 2;
    private int maxConcurrency = 10;
    private String configPath = "storage/config/ai-models.json";
    private DeepSplit deepSplit = new DeepSplit();
    private List<Model> models = new ArrayList<Model>();

    public String getSelectedId() { return selectedId; }
    public void setSelectedId(String selectedId) { this.selectedId = selectedId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public String getConfigPath() { return configPath; }
    public void setConfigPath(String configPath) { this.configPath = configPath; }
    public DeepSplit getDeepSplit() { return deepSplit; }
    public void setDeepSplit(DeepSplit deepSplit) { this.deepSplit = deepSplit; }
    public List<Model> getModels() { return models; }
    public void setModels(List<Model> models) { this.models = models; }

    public static class DeepSplit {
        private long chunkTimeoutSeconds = 180;
        private int maxRetries = 1;
        private int chunkParallelism = 10;
        private int chunkPromptChars = 9000;
        private int evidenceChars = 5000;
        private int maxRequestRows = 10000;

        public long getChunkTimeoutSeconds() { return chunkTimeoutSeconds; }
        public void setChunkTimeoutSeconds(long value) { this.chunkTimeoutSeconds = value; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int value) { this.maxRetries = value; }
        public int getChunkParallelism() { return chunkParallelism; }
        public void setChunkParallelism(int value) { this.chunkParallelism = value; }
        public int getChunkPromptChars() { return chunkPromptChars; }
        public void setChunkPromptChars(int value) { this.chunkPromptChars = value; }
        public int getEvidenceChars() { return evidenceChars; }
        public void setEvidenceChars(int value) { this.evidenceChars = value; }
        public int getMaxRequestRows() { return maxRequestRows; }
        public void setMaxRequestRows(int value) { this.maxRequestRows = value; }
    }

    public static class Model {
        private String id;
        private String name;
        private String provider;
        private String baseUrl;
        private String apiKey;
        private String model;
        private long timeoutSeconds;
        private int maxRetries;
        private int maxConcurrency;
        private long chunkTimeoutSeconds;
        private int chunkMaxRetries;
        private int chunkParallelism;
        private int chunkPromptChars;
        private int evidenceChars;
        private int maxRequestRows;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public long getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(long value) { this.timeoutSeconds = value; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int value) { this.maxRetries = value; }
        public int getMaxConcurrency() { return maxConcurrency; }
        public void setMaxConcurrency(int value) { this.maxConcurrency = value; }
        public long getChunkTimeoutSeconds() { return chunkTimeoutSeconds; }
        public void setChunkTimeoutSeconds(long value) { this.chunkTimeoutSeconds = value; }
        public int getChunkMaxRetries() { return chunkMaxRetries; }
        public void setChunkMaxRetries(int value) { this.chunkMaxRetries = value; }
        public int getChunkParallelism() { return chunkParallelism; }
        public void setChunkParallelism(int value) { this.chunkParallelism = value; }
        public int getChunkPromptChars() { return chunkPromptChars; }
        public void setChunkPromptChars(int value) { this.chunkPromptChars = value; }
        public int getEvidenceChars() { return evidenceChars; }
        public void setEvidenceChars(int value) { this.evidenceChars = value; }
        public int getMaxRequestRows() { return maxRequestRows; }
        public void setMaxRequestRows(int value) { this.maxRequestRows = value; }
    }
}
