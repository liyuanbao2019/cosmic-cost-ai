package com.li.jc.cosmiccostai.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiExtractionPromptRulesTest {

    @Test
    void promptIncludesSplitCheckRulesMarkdown() throws Exception {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        Method method = AiExtractionService.class.getDeclaredMethod("buildPrompt", String.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(service, "用户提交规则校验需求");

        assertTrue(prompt.contains("【内部拆分校验与可计量改写规则】"));
        assertTrue(prompt.contains("只认可开发功能且具备明确数据移动"));
        assertTrue(prompt.contains("规则校验"));
        assertTrue(prompt.contains("【需求细则】"));
    }
}
