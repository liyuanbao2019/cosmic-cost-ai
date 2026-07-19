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

@Service
public class SplitRuleConfigService {

    private static final String DEFAULT_RULE_RESOURCE = "static/split-check-rules.md";

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
