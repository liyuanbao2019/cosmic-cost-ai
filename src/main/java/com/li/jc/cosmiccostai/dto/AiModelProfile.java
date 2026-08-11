package com.li.jc.cosmiccostai.dto;

public class AiModelProfile {
    private String id;
    private String name;
    private String provider;
    private String baseUrl;
    private String apiKey;
    private boolean apiKeyConfigured;
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

    public AiModelProfile() {}

    public AiModelProfile(AiModelProfile source) {
        this.id = source.id; this.name = source.name; this.provider = source.provider;
        this.baseUrl = source.baseUrl; this.apiKey = source.apiKey; this.apiKeyConfigured = source.apiKeyConfigured;
        this.model = source.model; this.timeoutSeconds = source.timeoutSeconds; this.maxRetries = source.maxRetries;
        this.maxConcurrency = source.maxConcurrency; this.chunkTimeoutSeconds = source.chunkTimeoutSeconds;
        this.chunkMaxRetries = source.chunkMaxRetries; this.chunkParallelism = source.chunkParallelism;
        this.chunkPromptChars = source.chunkPromptChars; this.evidenceChars = source.evidenceChars;
        this.maxRequestRows = source.maxRequestRows;
    }

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
    public boolean isApiKeyConfigured() { return apiKeyConfigured; }
    public void setApiKeyConfigured(boolean value) { this.apiKeyConfigured = value; }
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
