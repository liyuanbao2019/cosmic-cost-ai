package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Maintains a user-corrected AI requirement input without letting automatic extraction overwrite it. */
@Service
public class RequirementInputService {

    private static final String MANUAL_INPUT_FLAG = "requirement-detail-manual.flag";

    private final FileStorageService fileStorageService;

    public RequirementInputService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    public void saveManualInput(String projectId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException("AI 输入内容不能为空");
        }
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        Path projectDir = fileStorageService.resolveProjectDir(projectId);
        Path inputPath = resolveInputPath(metadata, projectDir);
        write(inputPath, content.trim());
        write(projectDir.resolve(MANUAL_INPUT_FLAG), "manual");
        if (!inputPath.toString().equals(metadata.getRequirementDetailPath())) {
            metadata.setRequirementDetailPath(inputPath.toString());
            fileStorageService.saveMetadata(metadata);
        }
    }

    public void markCustomized(String projectId, ProjectMetadata metadata, String content) {
        Path projectDir = fileStorageService.resolveProjectDir(projectId);
        Path inputPath = resolveInputPath(metadata, projectDir);
        write(inputPath, content == null ? "" : content.trim());
        write(projectDir.resolve(MANUAL_INPUT_FLAG), "manual");
        if (!inputPath.toString().equals(metadata.getRequirementDetailPath())) {
            metadata.setRequirementDetailPath(inputPath.toString());
            fileStorageService.saveMetadata(metadata);
        }
    }

    public boolean isCustomized(Path projectDir) {
        return Files.exists(projectDir.resolve(MANUAL_INPUT_FLAG));
    }

    public void clearCustomization(String projectId) {
        try {
            Files.deleteIfExists(fileStorageService.resolveProjectDir(projectId).resolve(MANUAL_INPUT_FLAG));
        } catch (IOException ex) {
            throw new BusinessException("清除人工修订标记失败", ex);
        }
    }

    private Path resolveInputPath(ProjectMetadata metadata, Path projectDir) {
        return StringUtils.hasText(metadata.getRequirementDetailPath())
                ? Paths.get(metadata.getRequirementDetailPath())
                : projectDir.resolve("requirement-detail.txt");
    }

    private void write(Path path, String text) {
        try {
            Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BusinessException("保存 AI 输入内容失败", ex);
        }
    }
}
