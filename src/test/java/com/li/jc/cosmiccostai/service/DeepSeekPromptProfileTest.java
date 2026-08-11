package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.AiModelProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekPromptProfileTest {

    @Test
    void detectsDeepSeekFromProviderModelOrOfficialBaseUrl() {
        AiModelProfile profile = new AiModelProfile();
        profile.setProvider("deepseek");
        profile.setBaseUrl("https://gateway.example/v1");
        profile.setModel("custom-alias");
        assertTrue(DeepSeekPromptProfile.matches(profile));

        assertTrue(DeepSeekPromptProfile.matches("openai-compatible", "https://api.example/v1", "deepseek-v4-pro"));
        assertTrue(DeepSeekPromptProfile.matches("openai-compatible", "https://api.deepseek.com/v1", "alias"));
        assertFalse(DeepSeekPromptProfile.matches("openai", "https://api.openai.com/v1", "gpt-4o"));
    }

    @Test
    void addsExecutionProtocolWithoutCreatingAnotherBusinessRuleCopy() {
        String uniqueRuleMarker = "FULL-V2-RULE-UNIQUE-MARKER";
        String prompt = DeepSeekPromptProfile.specialize(
                AiPromptTask.EXTRACTION, "任务正文\n" + uniqueRuleMarker, "2026-v2");

        assertTrue(prompt.contains("DeepSeek 专项执行协议"));
        assertTrue(prompt.contains("唯一规则源：SplitRuleConfigService 当前生效完整规则"));
        assertTrue(prompt.contains("规则版本：2026-v2"));
        assertTrue(prompt.contains("{\"functions\":[...]}"));
        assertTrue(prompt.contains("{\"functions\":[]}"));
        assertTrue(prompt.indexOf(uniqueRuleMarker) == prompt.lastIndexOf(uniqueRuleMarker));
    }

    @Test
    void usesTaskSpecificObjectWrappers() {
        assertTrue(DeepSeekPromptProfile.specialize(AiPromptTask.INITIAL_REVIEW, "rules", "v2")
                .contains("{\"items\":[...]}"));
        assertTrue(DeepSeekPromptProfile.specialize(AiPromptTask.REUSE_ADJUSTMENT, "rules", "v2")
                .contains("{\"decisions\":[...]}"));
        assertTrue(DeepSeekPromptProfile.specialize(AiPromptTask.DEEP_REVIEW, "rules", "v2")
                .contains("{\"suggestions\":[...]}"));
        assertTrue(DeepSeekPromptProfile.systemPrompt(AiPromptTask.FUNCTION_DESCRIPTION)
                .contains("不重新拆分、不改变 E/R/W/X、复用度或 CFP"));
    }
}
