package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SplitRuleConfigService {

    private static final String DEFAULT_RULE_RESOURCE = "static/split-check-rules.md";
    /**
     * Bump whenever deterministic validation semantics change. The public rule
     * version must identify both the editable prose and the server-side judge.
     */
    private static final String DETERMINISTIC_RULE_ENGINE_REVISION = "2026-11-11.v2.8-transaction-skeleton";
    /** 规则文件标题中的内容版本号，例如“COSMIC 软件造价拆分基础规则 v2.8”。 */
    private static final Pattern RULE_CONTENT_VERSION = Pattern.compile(
            "COSMIC 软件造价拆分基础规则\\s*v(\\d+)\\.(\\d+)");

    private final Path editableRulePath;

    public SplitRuleConfigService(@Value("${cosmic.rules.editable-path:storage/config/split-check-rules.md}") String editablePath) {
        this.editableRulePath = Paths.get(editablePath);
    }

    public String getRules() {
        ensureEditableRuleFile();
        try {
            return new String(Files.readAllBytes(resolveEditableRulePath()), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BusinessException("读取初审规则失败：" + resolveEditableRulePath(), ex);
        }
    }

    public String getRulePath() {
        return resolveEditableRulePath().toString();
    }

    public String getRuleVersion() {
        String material = DETERMINISTIC_RULE_ENGINE_REVISION + "\n"
                + getRules() + "\n" + ActionMovementRule.canonicalPromptRules();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("R-");
            for (int i = 0; i < 8 && i < hash.length; i++) {
                value.append(String.format("%02x", hash[i] & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * The exact rule package supplied to every AI stage. Editable audit rules
     * are supplemental; the deterministic action catalogue is appended last
     * so prose edits cannot silently override server-side validation.
     */
    public String getEffectiveRulesPrompt() {
        return "【当前生效规则版本：" + getRuleVersion() + "】\n"
                + "【可编辑审计规则】\n" + getRules().trim() + "\n"
                + "【系统统一动作目录（机器强制）】\n" + ActionMovementRule.canonicalPromptRules()
                + "如可编辑规则与系统统一动作目录冲突，以系统统一动作目录和原文证据约束为准。\n";
    }

    public String saveRules(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException("初审规则内容不能为空");
        }
        Path path = resolveEditableRulePath();
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
            return content;
        } catch (IOException ex) {
            throw new BusinessException("保存初审规则失败：" + path, ex);
        }
    }

    private void ensureEditableRuleFile() {
        Path path = resolveEditableRulePath();
        if (Files.exists(path)) {
            upgradeLegacyBundledRules(path);
            return;
        }
        String defaultRules = readDefaultRules();
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, defaultRules.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BusinessException("初始化初审规则失败：" + path, ex);
        }
    }

    /**
     * 运行副本迁移：按标题中的内容版本号判断，不只看标题文字。
     * 运行副本版本低于打包版本（或是更早的无版本旧规则）时，先备份旧文件，再整份替换为打包版本；
     * 版本相同或更高时视为用户维护的规则，保持不动。
     */
    private void upgradeLegacyBundledRules(Path path) {
        try {
            String existing = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            String bundled = readDefaultRules();
            int[] bundledVersion = contentVersion(bundled);
            int[] existingVersion = contentVersion(existing);
            boolean outdated;
            if (existingVersion != null) {
                outdated = bundledVersion != null && compareVersion(existingVersion, bundledVersion) < 0;
            } else {
                outdated = existing.startsWith("# 拆分校验规则")
                        && (existing.contains("暂不按“三要素相同/两项相同”自动判定不同功能过程")
                        || existing.contains("X 后不得再出现 E/R/W")
                        || existing.contains("X 输出后再次出现 E"));
            }
            if (!outdated) {
                return;
            }
            backupRuleFile(path, existing, existingVersion);
            Files.write(path, bundled.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BusinessException("升级统一拆分规则失败：" + path, ex);
        }
    }

    /** 规则文件内容版本号，如 v2.8；无法识别时返回空字符串。 */
    public String getRuleContentVersion() {
        int[] version = contentVersion(getRules());
        return version == null ? "" : "v" + version[0] + "." + version[1];
    }

    static int[] contentVersion(String rules) {
        if (rules == null) {
            return null;
        }
        Matcher matcher = RULE_CONTENT_VERSION.matcher(rules);
        if (!matcher.find()) {
            return null;
        }
        return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    private static int compareVersion(int[] left, int[] right) {
        if (left[0] != right[0]) {
            return left[0] < right[0] ? -1 : 1;
        }
        return Integer.compare(left[1], right[1]);
    }

    private void backupRuleFile(Path path, String content, int[] version) throws IOException {
        String label = version == null ? "legacy" : "v" + version[0] + "." + version[1];
        String stamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        Path backup = path.resolveSibling(path.getFileName().toString().replaceAll("\\.md$", "")
                + "." + label + "." + stamp + ".bak.md");
        Files.write(backup, content.getBytes(StandardCharsets.UTF_8));
    }

    private Path resolveEditableRulePath() {
        Path path = editableRulePath;
        if (!path.isAbsolute()) {
            path = Paths.get("").toAbsolutePath().resolve(path);
        }
        return path.normalize();
    }

    private String readDefaultRules() {
        ClassPathResource resource = new ClassPathResource(DEFAULT_RULE_RESOURCE);
        if (!resource.exists()) {
            return "# 拆分校验规则" + System.lineSeparator();
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return readAll(inputStream);
        } catch (IOException ex) {
            throw new BusinessException("读取默认初审规则失败", ex);
        }
    }

    private String readAll(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        while ((read = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }
}
