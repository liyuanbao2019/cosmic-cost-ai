package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.AiReviewValidationItem;
import com.li.jc.cosmiccostai.dto.AiReviewValidationRow;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiReviewValidationDeepSeekPromptTest {

    @Test
    void initialReviewUsesDeepSeekProtocolAndSingleCompleteRuleSource() throws Exception {
        AiReviewValidationService service = service();
        Method method = AiReviewValidationService.class.getDeclaredMethod("buildPrompt", List.class);
        method.setAccessible(true);

        AiReviewValidationRow reviewRow = new AiReviewValidationRow();
        reviewRow.setRowIndex(1);
        FunctionSplitRow row = new FunctionSplitRow();
        row.setFunctionProcess("查询客户");
        row.setSubProcessDescription("读取客户数据");
        row.setMovementType("R");
        reviewRow.setRow(row);
        String prompt = (String) method.invoke(service, Collections.singletonList(reviewRow));
        String ruleTitle = "COSMIC 软件造价拆分基础规则 v2.0（设计院评审沉淀版）";

        assertTrue(prompt.contains("DeepSeek 专项执行协议"));
        assertTrue(prompt.contains("顶层为 items 数组"));
        assertTrue(prompt.contains("{\"items\":[]}"));
        assertTrue(prompt.contains("reviewContextRows"));
        assertTrue(prompt.contains("suggestedReuseDegree,suggestedCfp"));
        assertTrue(prompt.indexOf(ruleTitle) >= 0);
        assertTrue(prompt.indexOf(ruleTitle) == prompt.lastIndexOf(ruleTitle));
        assertFalse(prompt.contains("只输出 JSON 数组"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsDeepSeekObjectWrapperForInitialReview() throws Exception {
        AiReviewValidationService service = service();
        Method method = AiReviewValidationService.class.getDeclaredMethod("parseItems", String.class);
        method.setAccessible(true);

        List<AiReviewValidationItem> items = (List<AiReviewValidationItem>) method.invoke(service,
                "{\"items\":[{\"rowIndex\":1,\"verdict\":\"FALSE_POSITIVE\",\"reason\":\"规则误报\",\"suggestion\":\"无需修改\"}]}");

        assertEquals(1, items.size());
        assertEquals("FALSE_POSITIVE", items.get(0).getVerdict());
    }

    @Test
    void normalizesConfirmedReuseSuggestionToCanonicalCfp() throws Exception {
        AiReviewValidationService service = service();
        AiReviewValidationItem item = new AiReviewValidationItem();
        item.setVerdict("CONFIRMED_RISK");
        item.setSuggestedReuseDegree("复用");
        item.setSuggestedCfp(1D);
        Method method = AiReviewValidationService.class.getDeclaredMethod("normalizeReuseSuggestions", List.class);
        method.setAccessible(true);

        method.invoke(service, Collections.singletonList(item));

        assertEquals("复用", item.getSuggestedReuseDegree());
        assertEquals(0.33D, item.getSuggestedCfp(), 0.0001D);
    }

    private AiReviewValidationService service() {
        return new AiReviewValidationService(
                "https://api.deepseek.com/v1", "", "deepseek-v4-pro", 30, 0, null,
                new SplitRuleConfigService("target/deepseek-review-test-config/split-check-rules.md"));
    }
}
