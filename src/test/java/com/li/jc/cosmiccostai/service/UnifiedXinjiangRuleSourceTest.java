package com.li.jc.cosmiccostai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedXinjiangRuleSourceTest {

    private static final String TITLE = "COSMIC 软件造价拆分基础规则 v2.8（事务三档法 + 拆分输出约束版）";

    @TempDir
    Path tempDir;

    @Test
    void exposesOneUnifiedV28RuleWithTransactionMethodAndXinjiangScenarios() {
        SplitRuleConfigService service = new SplitRuleConfigService(
                tempDir.resolve("split-check-rules.md").toString());

        String rules = service.getRules();
        String effective = service.getEffectiveRulesPrompt();

        assertTrue(rules.contains(TITLE));
        assertEquals("v2.8", service.getRuleContentVersion());
        // v2.8 核心口径
        assertTrue(rules.contains("事务三档法"));
        assertTrue(rules.contains("三行骨架"));
        assertTrue(rules.contains("六类需求拆分模板"));
        assertTrue(rules.contains("V46"));
        assertTrue(rules.contains("V47"));
        assertTrue(rules.contains("V48"));
        assertTrue(rules.contains("导出 = E-R-X"));
        // 新疆四类特殊场景仍保留
        assertTrue(rules.contains("新疆专项：SFTP 文件接入及共享"));
        assertTrue(rules.contains("新疆专项：数据库中数据汇总"));
        assertTrue(rules.contains("新疆专项：Kafka 接入及共享"));
        assertTrue(rules.contains("新疆专项：实时交互接口"));
        // 复用 CFP 统一精确 1/3，规则正文不再出现 0.33 作为系数
        assertTrue(rules.contains("精确 1/3"));
        assertFalse(rules.contains("复用 0.33"));
        assertEquals(effective.indexOf(TITLE), effective.lastIndexOf(TITLE));
        assertFalse(effective.contains("地区规则补充"));
    }

    @Test
    void bundledAndRuntimeRuleFilesStayIdentical() throws IOException {
        Path bundled = Paths.get("src/main/resources/static/split-check-rules.md");
        Path runtime = Paths.get("storage/config/split-check-rules.md");
        if (!Files.exists(runtime)) {
            return;
        }
        assertEquals(new String(Files.readAllBytes(bundled), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(runtime), StandardCharsets.UTF_8));
    }

    @Test
    void migratesV20RuntimeRulesAndKeepsBackup() throws Exception {
        Path editable = tempDir.resolve("split-check-rules.md");
        Files.write(editable, "# COSMIC 软件造价拆分基础规则 v2.0（设计院评审沉淀版）\n旧内容"
                .getBytes(StandardCharsets.UTF_8));

        String rules = new SplitRuleConfigService(editable.toString()).getRules();

        assertTrue(rules.contains(TITLE));
        assertFalse(rules.contains("\n旧内容"));
        List<Path> backups = backups();
        assertEquals(1, backups.size());
        assertTrue(backups.get(0).getFileName().toString().startsWith("split-check-rules.v2.0."));
        assertTrue(new String(Files.readAllBytes(backups.get(0)), StandardCharsets.UTF_8).contains("旧内容"));
    }

    @Test
    void migratesV21RuntimeRulesByContentVersionNotTitleText() throws Exception {
        Path editable = tempDir.resolve("split-check-rules.md");
        Files.write(editable, ("# COSMIC 软件造价拆分基础规则 v2.1（用户改过的标题）\n"
                + "用户自定义段落").getBytes(StandardCharsets.UTF_8));

        String rules = new SplitRuleConfigService(editable.toString()).getRules();

        assertTrue(rules.contains(TITLE));
        assertEquals(1, backups().size());
        assertTrue(new String(Files.readAllBytes(backups().get(0)), StandardCharsets.UTF_8)
                .contains("用户自定义段落"));
    }

    @Test
    void keepsUserEditedRulesOfSameOrNewerVersion() throws Exception {
        Path same = tempDir.resolve("same.md");
        Files.write(same, "# COSMIC 软件造价拆分基础规则 v2.8（本地修订）\n本地补充条款"
                .getBytes(StandardCharsets.UTF_8));
        Path newer = tempDir.resolve("newer.md");
        Files.write(newer, "# COSMIC 软件造价拆分基础规则 v2.10\n更新的规则"
                .getBytes(StandardCharsets.UTF_8));

        assertTrue(new SplitRuleConfigService(same.toString()).getRules().contains("本地补充条款"));
        assertTrue(new SplitRuleConfigService(newer.toString()).getRules().contains("更新的规则"));
        assertTrue(backups().isEmpty());
    }

    @Test
    void parsesContentVersion() {
        assertArrayEquals(new int[]{2, 8}, SplitRuleConfigService.contentVersion(TITLE));
        assertArrayEquals(new int[]{2, 10},
                SplitRuleConfigService.contentVersion("# COSMIC 软件造价拆分基础规则 v2.10"));
        assertNull(SplitRuleConfigService.contentVersion("# 拆分校验规则"));
    }

    private List<Path> backups() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".bak.md")).collect(Collectors.toList());
        }
    }
}
