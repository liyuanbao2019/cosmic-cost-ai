package com.li.jc.cosmiccostai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "cosmic.storage.base-dir=target/context-smoke/storage",
        "cosmic.ai.config-path=target/context-smoke/ai-models.json",
        "cosmic.rules.editable-path=target/context-smoke/split-check-rules.md"
})
class CosmicCostAiApplicationContextTest {

    @Test
    void contextLoadsWithUnifiedSmartSplitServices() {
        // Spring creates the controllers, executors and both single/batch
        // orchestrators; successful startup is the assertion.
    }
}
