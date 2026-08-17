package com.li.jc.cosmiccostai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedXinjiangRuleSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesOneUnifiedRuleWithAllXinjiangSpecialScenarios() {
        SplitRuleConfigService service = new SplitRuleConfigService(
                tempDir.resolve("split-check-rules.md").toString());

        String rules = service.getRules();
        String effective = service.getEffectiveRulesPrompt();
        String title = "COSMIC 软件造价拆分基础规则 v2.1（设计院评审 + 新疆特殊场景沉淀版）";

        assertTrue(rules.contains(title));
        assertTrue(rules.contains("新疆专项：SFTP 文件接入及共享"));
        assertTrue(rules.contains("新疆专项：数据库中数据汇总"));
        assertTrue(rules.contains("新疆专项：Kafka 接入及共享"));
        assertTrue(rules.contains("新疆专项：实时交互接口"));
        assertTrue(rules.contains("定时/系统事件初始触发 E 默认按 **复用 0.33**"));
        assertEquals(effective.indexOf(title), effective.lastIndexOf(title));
        assertFalse(effective.contains("XINJIANG_V2_1"));
        assertFalse(effective.contains("地区规则补充"));
    }

    @Test
    void migratesExistingV20RuntimeRulesToTheUnifiedV21Source() throws Exception {
        Path editable = tempDir.resolve("legacy-v2.0-rules.md");
        Files.write(editable,
                "# COSMIC 软件造价拆分基础规则 v2.0（设计院评审沉淀版）\n旧内容"
                        .getBytes(StandardCharsets.UTF_8));

        String rules = new SplitRuleConfigService(editable.toString()).getRules();

        assertTrue(rules.contains("COSMIC 软件造价拆分基础规则 v2.1"));
        assertTrue(rules.contains("新疆专项：Kafka 接入及共享"));
        assertFalse(rules.contains("\n旧内容"));
    }
}
