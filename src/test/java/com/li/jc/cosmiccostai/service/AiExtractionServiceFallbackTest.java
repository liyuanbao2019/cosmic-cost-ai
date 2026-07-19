package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.ExtractedFunction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiExtractionServiceFallbackTest {

    @Test
    void fallsBackToNumberedRuleOptimizationFunctionsWhenAiReturnsEmptyArray() throws Exception {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        Method method = AiExtractionService.class.getDeclaredMethod("fallbackFunctions", String.class);
        method.setAccessible(true);

        String detail = "（1）统计规则优化：\n"
                + "按照原有既定规则，当匹配规则执行至标准十级地址剔重的环节时，变更为以标准十级地址ID字段进行剔重。\n"
                + "（2）明细字段优化：\n"
                + "在宽带业务稽核报表及下钻明细中调整当月零流量、三个月零流量、地址ID剔重等字段。\n";

        @SuppressWarnings("unchecked")
        List<ExtractedFunction> functions = (List<ExtractedFunction>) method.invoke(service, detail);

        assertEquals(2, functions.size());
        assertEquals("统计规则优化", functions.get(0).getFunction());
        assertTrue(functions.get(1).getDescription().contains("宽带业务稽核"));
    }
}
