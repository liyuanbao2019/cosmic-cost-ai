package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.config.AiModelProperties;
import com.li.jc.cosmiccostai.dto.AiModelProfile;
import com.li.jc.cosmiccostai.dto.AiModelSettings;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class AiModelConfigService {

    private static final String MASK_PREFIX = "********";
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final DeepSeekChatClient deepSeekChatClient = new DeepSeekChatClient(objectMapper);
    private final Path configPath;
    private final long extractionTimeoutSeconds;
    private final Map<String, Semaphore> limiters = new ConcurrentHashMap<String, Semaphore>();
    private volatile AiModelSettings settings;

    public AiModelConfigService(AiModelProperties properties) {
        this.configPath = Paths.get(properties.getConfigPath()).toAbsolutePath().normalize();
        AiModelProperties.Extraction extraction = properties.getExtraction() == null
                ? new AiModelProperties.Extraction() : properties.getExtraction();
        this.extractionTimeoutSeconds = Math.max(5L, extraction.getTimeoutSeconds());
        this.settings = load(properties);
    }

    public synchronized AiModelSettings getSettings() {
        AiModelSettings response = copy(settings, false);
        response.setConfigPath(configPath.toString());
        for (AiModelProfile profile : response.getModels()) {
            profile.setApiKeyConfigured(StringUtils.hasText(profile.getApiKey()));
            profile.setApiKey(mask(profile.getApiKey()));
        }
        return response;
    }

    public synchronized AiModelSettings save(AiModelSettings request) {
        AiModelSettings normalized = normalize(request, settings);
        persist(normalized);
        this.settings = normalized;
        this.limiters.clear();
        return getSettings();
    }

    public AiModelProfile getActiveProfile() {
        AiModelSettings snapshot = settings;
        for (AiModelProfile profile : snapshot.getModels()) {
            if (profile.getId().equals(snapshot.getSelectedId())) {
                return new AiModelProfile(profile);
            }
        }
        throw new BusinessException("当前选择的大模型配置不存在，请进入大模型管理重新选择。");
    }

    public boolean isDeepSeekActive() {
        return DeepSeekPromptProfile.matches(getActiveProfile());
    }

    public String generate(String prompt, boolean deepSplit) {
        return generate(prompt, deepSplit, deepSplit ? 0 : -1);
    }

    public String generate(String prompt, boolean deepSplit, int maxRetriesOverride) {
        return generateForTask(prompt, AiPromptTask.GENERIC, deepSplit, maxRetriesOverride);
    }

    public String generateForTask(String prompt, AiPromptTask task, boolean deepSplit) {
        return generateForTask(prompt, task, deepSplit, deepSplit ? 0 : -1);
    }

    public String generateForTask(String prompt,
                                  AiPromptTask task,
                                  boolean deepSplit,
                                  String requestId,
                                  AiRequestAttemptListener listener) {
        int retries = task == AiPromptTask.EXTRACTION ? 0 : (deepSplit ? 0 : -1);
        return generateForTask(prompt, task, deepSplit, retries, requestId, listener);
    }

    public String generateForTask(String prompt, AiPromptTask task, boolean deepSplit, int maxRetriesOverride) {
        return generateForTask(prompt, task, deepSplit, maxRetriesOverride, null, null);
    }

    private String generateForTask(String prompt,
                                   AiPromptTask task,
                                   boolean deepSplit,
                                   int maxRetriesOverride,
                                   String requestId,
                                   AiRequestAttemptListener listener) {
        AiModelProfile profile = getActiveProfile();
        String apiKey = resolveApiKey(profile.getApiKey());
        if (!StringUtils.hasText(apiKey)) {
            throw new BusinessException("大模型 API Key 未配置，请进入“大模型管理”设置。");
        }
        long timeout = task == AiPromptTask.EXTRACTION
                ? extractionTimeoutSeconds
                : (deepSplit ? profile.getChunkTimeoutSeconds() : profile.getTimeoutSeconds());
        int retries = maxRetriesOverride >= 0 ? maxRetriesOverride : profile.getMaxRetries();
        Semaphore limiter = limiter(profile);
        boolean acquired = false;
        try {
            acquired = limiter.tryAcquire(Math.max(10, timeout), TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException("大模型并发已达到上限（" + profile.getMaxConcurrency() + "），等待超时，请稍后重试。");
            }
            if (task != null && task != AiPromptTask.GENERIC && DeepSeekPromptProfile.matches(profile)) {
                return deepSeekChatClient.generate(profile, apiKey, task, prompt, timeout, retries,
                        requestId, listener);
            }
            if (listener != null) {
                listener.onAttempt(1, 1, timeout);
            }
            ChatLanguageModel chatModel = OpenAiChatModel.builder()
                    .baseUrl(profile.getBaseUrl())
                    .apiKey(apiKey)
                    .modelName(profile.getModel())
                    .temperature(0.0)
                    .timeout(Duration.ofSeconds(timeout))
                    .maxRetries(Math.max(0, retries))
                    .build();
            return chatModel.generate(prompt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("等待大模型并发名额时任务被中断。", ex);
        } finally {
            if (acquired) {
                limiter.release();
            }
        }
    }

    public void beginCancellableRequest(String requestId) {
        deepSeekChatClient.beginRequest(requestId);
    }

    public void endCancellableRequest(String requestId) {
        deepSeekChatClient.endRequest(requestId);
    }

    public boolean cancelRequest(String requestId) {
        return deepSeekChatClient.cancel(requestId);
    }

    private Semaphore limiter(AiModelProfile profile) {
        String key = profile.getId() + ":" + profile.getMaxConcurrency();
        Semaphore current = limiters.get(key);
        if (current != null) {
            return current;
        }
        Semaphore created = new Semaphore(Math.max(1, profile.getMaxConcurrency()), true);
        Semaphore raced = limiters.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    private AiModelSettings load(AiModelProperties properties) {
        if (Files.isRegularFile(configPath)) {
            try {
                AiModelSettings stored = objectMapper.readValue(Files.readAllBytes(configPath), AiModelSettings.class);
                return normalize(stored, null);
            } catch (Exception ex) {
                throw new BusinessException("读取大模型配置失败：" + configPath, ex);
            }
        }
        AiModelSettings defaults = fromProperties(properties);
        persist(defaults);
        return defaults;
    }

    private AiModelSettings fromProperties(AiModelProperties properties) {
        AiModelSettings result = new AiModelSettings();
        result.setSelectedId(properties.getSelectedId());
        List<AiModelProfile> profiles = new ArrayList<AiModelProfile>();
        if (properties.getModels() != null) {
            for (AiModelProperties.Model model : properties.getModels()) {
                AiModelProfile profile = new AiModelProfile();
                profile.setId(model.getId()); profile.setName(model.getName()); profile.setProvider(model.getProvider());
                profile.setBaseUrl(model.getBaseUrl()); profile.setApiKey(model.getApiKey()); profile.setModel(model.getModel());
                profile.setTimeoutSeconds(model.getTimeoutSeconds()); profile.setMaxRetries(model.getMaxRetries());
                profile.setMaxConcurrency(model.getMaxConcurrency()); profile.setChunkTimeoutSeconds(model.getChunkTimeoutSeconds());
                profile.setChunkMaxRetries(model.getChunkMaxRetries()); profile.setChunkParallelism(model.getChunkParallelism());
                profile.setChunkPromptChars(model.getChunkPromptChars()); profile.setEvidenceChars(model.getEvidenceChars());
                profile.setMaxRequestRows(model.getMaxRequestRows());
                fillDefaults(profile, properties);
                profiles.add(profile);
            }
        }
        if (profiles.isEmpty()) {
            AiModelProfile legacy = new AiModelProfile();
            legacy.setId(StringUtils.hasText(properties.getSelectedId()) ? properties.getSelectedId() : "default");
            legacy.setName("默认模型"); legacy.setProvider(properties.getProvider()); legacy.setBaseUrl(properties.getBaseUrl());
            legacy.setApiKey(properties.getApiKey()); legacy.setModel(properties.getModel());
            fillDefaults(legacy, properties);
            profiles.add(legacy);
            result.setSelectedId(legacy.getId());
        }
        result.setModels(profiles);
        return normalize(result, null);
    }

    private void fillDefaults(AiModelProfile profile, AiModelProperties properties) {
        if (!StringUtils.hasText(profile.getProvider())) profile.setProvider(properties.getProvider());
        if (!StringUtils.hasText(profile.getBaseUrl())) profile.setBaseUrl(properties.getBaseUrl());
        if (!StringUtils.hasText(profile.getModel())) profile.setModel(properties.getModel());
        if (profile.getTimeoutSeconds() <= 0) profile.setTimeoutSeconds(properties.getTimeoutSeconds());
        if (profile.getMaxRetries() < 0) profile.setMaxRetries(properties.getMaxRetries());
        if (profile.getMaxConcurrency() <= 0) profile.setMaxConcurrency(properties.getMaxConcurrency());
        if (profile.getChunkTimeoutSeconds() <= 0) profile.setChunkTimeoutSeconds(properties.getDeepSplit().getChunkTimeoutSeconds());
        if (profile.getChunkMaxRetries() < 0) profile.setChunkMaxRetries(properties.getDeepSplit().getMaxRetries());
        if (profile.getChunkParallelism() <= 0) profile.setChunkParallelism(properties.getDeepSplit().getChunkParallelism());
        if (profile.getChunkPromptChars() <= 0) profile.setChunkPromptChars(properties.getDeepSplit().getChunkPromptChars());
        if (profile.getEvidenceChars() <= 0) profile.setEvidenceChars(properties.getDeepSplit().getEvidenceChars());
        if (profile.getMaxRequestRows() <= 0) profile.setMaxRequestRows(properties.getDeepSplit().getMaxRequestRows());
    }

    private AiModelSettings normalize(AiModelSettings request, AiModelSettings previous) {
        if (request == null || request.getModels() == null || request.getModels().isEmpty()) {
            throw new BusinessException("至少需要保留一个大模型配置。");
        }
        Map<String, String> oldKeys = new HashMap<String, String>();
        if (previous != null) for (AiModelProfile old : previous.getModels()) oldKeys.put(old.getId(), old.getApiKey());
        AiModelSettings result = new AiModelSettings();
        result.setSelectedId(text(request.getSelectedId()));
        Set<String> ids = new HashSet<String>();
        List<AiModelProfile> profiles = new ArrayList<AiModelProfile>();
        for (AiModelProfile input : request.getModels()) {
            AiModelProfile profile = new AiModelProfile(input);
            profile.setId(text(profile.getId())); profile.setName(text(profile.getName()));
            profile.setProvider(text(profile.getProvider())); profile.setBaseUrl(text(profile.getBaseUrl()));
            profile.setModel(text(profile.getModel()));
            if (!StringUtils.hasText(profile.getId()) || !profile.getId().matches("[A-Za-z0-9_-]{1,64}"))
                throw new BusinessException("模型 ID 只能包含字母、数字、下划线和短横线，长度不超过 64。");
            if (!ids.add(profile.getId())) throw new BusinessException("模型 ID 不能重复：" + profile.getId());
            if (!StringUtils.hasText(profile.getName()) || !StringUtils.hasText(profile.getBaseUrl()) || !StringUtils.hasText(profile.getModel()))
                throw new BusinessException("模型名称、Base URL 和模型标识不能为空。");
            String incomingKey = text(profile.getApiKey());
            if (!StringUtils.hasText(incomingKey) || incomingKey.startsWith(MASK_PREFIX)) profile.setApiKey(oldKeys.get(profile.getId()));
            profile.setTimeoutSeconds(range(profile.getTimeoutSeconds(), 5, 3600, "普通调用超时"));
            profile.setMaxRetries((int) range(profile.getMaxRetries(), 0, 10, "重试次数"));
            profile.setMaxConcurrency((int) range(profile.getMaxConcurrency(), 1, 10000, "最大并发"));
            profile.setChunkTimeoutSeconds(range(profile.getChunkTimeoutSeconds(), 20, 3600, "分批调用超时"));
            profile.setChunkMaxRetries((int) range(profile.getChunkMaxRetries(), 0, 10, "分批重试次数"));
            profile.setChunkParallelism((int) range(profile.getChunkParallelism(), 1, 100, "分批并发"));
            profile.setChunkPromptChars((int) range(profile.getChunkPromptChars(), 1000, 200000, "单批提示词字符数"));
            profile.setEvidenceChars((int) range(profile.getEvidenceChars(), 500, 100000, "证据字符数"));
            profile.setMaxRequestRows((int) range(profile.getMaxRequestRows(), 20, 100000, "最大请求行数"));
            profiles.add(profile);
        }
        if (!ids.contains(result.getSelectedId())) throw new BusinessException("请选择一个存在的大模型作为当前模型。");
        result.setModels(profiles);
        return result;
    }

    private long range(long value, long min, long max, String name) {
        if (value < min || value > max) throw new BusinessException(name + "必须在 " + min + " 到 " + max + " 之间。");
        return value;
    }

    private void persist(AiModelSettings value) {
        try {
            Files.createDirectories(configPath.getParent());
            Path temp = configPath.resolveSibling(configPath.getFileName().toString() + ".tmp");
            Files.write(temp, objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupported) {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new BusinessException("保存大模型配置失败：" + configPath, ex);
        }
    }

    private AiModelSettings copy(AiModelSettings source, boolean includePath) {
        AiModelSettings result = new AiModelSettings();
        result.setSelectedId(source.getSelectedId());
        if (includePath) result.setConfigPath(source.getConfigPath());
        List<AiModelProfile> profiles = new ArrayList<AiModelProfile>();
        for (AiModelProfile profile : source.getModels()) profiles.add(new AiModelProfile(profile));
        result.setModels(profiles);
        return result;
    }

    private String mask(String key) {
        if (!StringUtils.hasText(key)) return "";
        return MASK_PREFIX + (key.length() <= 4 ? "" : key.substring(key.length() - 4));
    }

    private String resolveApiKey(String configured) {
        if (StringUtils.hasText(configured)) return configured;
        String value = System.getProperty("OPENAI_API_KEY");
        if (!StringUtils.hasText(value)) value = System.getProperty("DEEPSEEK_API_KEY");
        if (!StringUtils.hasText(value)) value = System.getenv("OPENAI_API_KEY");
        if (!StringUtils.hasText(value)) value = System.getenv("DEEPSEEK_API_KEY");
        return value;
    }

    private String text(String value) { return value == null ? "" : value.trim(); }
}
