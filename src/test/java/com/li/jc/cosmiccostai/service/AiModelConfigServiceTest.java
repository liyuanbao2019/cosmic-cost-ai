package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.config.AiModelProperties;
import com.li.jc.cosmiccostai.dto.AiModelProfile;
import com.li.jc.cosmiccostai.dto.AiModelSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelConfigServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsSelectionAndPreservesMaskedApiKey() throws Exception {
        Path config = tempDir.resolve("ai-models.json");
        AiModelProperties properties = new AiModelProperties();
        properties.setConfigPath(config.toString());
        properties.setSelectedId("first");
        properties.setBaseUrl("https://example.invalid/v1");
        properties.setApiKey("secret-key-1234");
        properties.setModel("model-a");

        AiModelConfigService service = new AiModelConfigService(properties);
        AiModelSettings response = service.getSettings();

        assertEquals("first", response.getSelectedId());
        assertTrue(response.getModels().get(0).isApiKeyConfigured());
        assertTrue(response.getModels().get(0).getApiKey().endsWith("1234"));
        assertFalse(response.getModels().get(0).getApiKey().contains("secret-key"));

        AiModelProfile second = new AiModelProfile(response.getModels().get(0));
        second.setId("second");
        second.setName("第二个模型");
        second.setModel("model-b");
        second.setApiKey("another-secret");
        response.getModels().add(second);
        response.setSelectedId("second");
        response.getModels().get(0).setTimeoutSeconds(600);
        response.getModels().get(0).setMaxConcurrency(1000);
        service.save(response);

        String stored = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        assertTrue(stored.contains("secret-key-1234"));
        assertTrue(stored.contains("another-secret"));
        assertEquals("second", service.getActiveProfile().getId());
        assertEquals(600, service.getSettings().getModels().get(0).getTimeoutSeconds());
        assertEquals(1000, service.getSettings().getModels().get(0).getMaxConcurrency());
    }

    @Test
    void pinsProfileForAllPartitionsOfOneRunningStage() {
        Path config = tempDir.resolve("pinned-ai-models.json");
        AiModelProperties properties = new AiModelProperties();
        properties.setConfigPath(config.toString());
        properties.setSelectedId("deepseek");
        properties.setProvider("deepseek");
        properties.setBaseUrl("https://deepseek.invalid/v1");
        properties.setApiKey("deepseek-key");
        properties.setModel("deepseek-model");
        AiModelConfigService service = new AiModelConfigService(properties);

        service.beginCancellableRequest("project-1");
        AiModelSettings changed = service.getSettings();
        AiModelProfile alternate = new AiModelProfile(changed.getModels().get(0));
        alternate.setId("alternate");
        alternate.setName("Alternate");
        alternate.setProvider("openai-compatible");
        alternate.setBaseUrl("https://alternate.invalid/v1");
        alternate.setModel("alternate-model");
        alternate.setApiKey("alternate-key");
        changed.getModels().add(alternate);
        changed.setSelectedId("alternate");
        service.save(changed);

        assertEquals("deepseek", service.profileForRequest("project-1").getId());
        service.endCancellableRequest("project-1");
        assertEquals("alternate", service.profileForRequest("project-1").getId());
    }
}
