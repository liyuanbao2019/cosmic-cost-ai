package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.UploadResponse;
import com.example.cosmiccostai.model.ProjectMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final DateTimeFormatter ID_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path storageBaseDir;
    private final ObjectMapper objectMapper;

    public FileStorageService(@Value("${cosmic.storage.base-dir:storage}") String storageBaseDir) {
        this.storageBaseDir = Paths.get(storageBaseDir);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public UploadResponse upload(MultipartFile file, String projectName) {
        validate(file);

        String originalFileName = cleanOriginalFileName(file.getOriginalFilename());
        String fileType = getExtension(originalFileName);
        String projectId = generateProjectId();
        String resolvedProjectName = resolveProjectName(projectName, originalFileName);

        Path projectDir = resolveProjectDir(projectId);
        Path filesDir = projectDir.resolve("files");
        Path storedFile = filesDir.resolve("original." + fileType);

        try {
            Files.createDirectories(filesDir);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, storedFile, StandardCopyOption.REPLACE_EXISTING);
            }

            String now = LocalDateTime.now().format(DATE_TIME_FORMATTER);
            ProjectMetadata metadata = new ProjectMetadata();
            metadata.setId(projectId);
            metadata.setName(resolvedProjectName);
            metadata.setSourceFileName(originalFileName);
            metadata.setStoredFilePath(storedFile.toString());
            metadata.setFileSize(file.getSize());
            metadata.setFileType(fileType);
            metadata.setStatus("UPLOADED");
            metadata.setCreatedAt(now);
            metadata.setUpdatedAt(now);

            saveMetadata(metadata);

            UploadResponse response = new UploadResponse();
            response.setProjectId(metadata.getId());
            response.setProjectName(metadata.getName());
            response.setSourceFileName(metadata.getSourceFileName());
            response.setStoredFilePath(metadata.getStoredFilePath());
            response.setFileSize(metadata.getFileSize());
            response.setFileType(metadata.getFileType());
            response.setStatus(metadata.getStatus());
            response.setCreatedAt(metadata.getCreatedAt());
            return response;
        } catch (IOException ex) {
            throw new BusinessException("Save uploaded file failed", ex);
        }
    }

    public ProjectMetadata uploadSplitExcel(MultipartFile file, String projectName) {
        validateSplitExcel(file);

        String originalFileName = cleanOriginalFileName(file.getOriginalFilename());
        String fileType = getExtension(originalFileName);
        String projectId = generateProjectId();
        String resolvedProjectName = resolveProjectName(projectName, originalFileName);

        Path projectDir = resolveProjectDir(projectId);
        Path filesDir = projectDir.resolve("files");
        Path storedFile = filesDir.resolve("split-review." + fileType);

        try {
            Files.createDirectories(filesDir);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, storedFile, StandardCopyOption.REPLACE_EXISTING);
            }

            String now = LocalDateTime.now().format(DATE_TIME_FORMATTER);
            ProjectMetadata metadata = new ProjectMetadata();
            metadata.setId(projectId);
            metadata.setName(resolvedProjectName);
            metadata.setSourceFileName(originalFileName);
            metadata.setStoredFilePath(storedFile.toString());
            metadata.setFileSize(file.getSize());
            metadata.setFileType(fileType);
            metadata.setCosmicResultsPath(projectDir.resolve("cosmic-results.json").toString());
            metadata.setStatus("EXCEL_UPLOADED");
            metadata.setCreatedAt(now);
            metadata.setUpdatedAt(now);

            saveMetadata(metadata);
            return metadata;
        } catch (IOException ex) {
            throw new BusinessException("Save uploaded Excel failed", ex);
        }
    }

    public ProjectMetadata loadMetadata(String projectId) {
        Path metadataPath = resolveProjectDir(projectId).resolve("metadata.json");
        if (!Files.exists(metadataPath)) {
            throw new BusinessException("Project not found: " + projectId);
        }
        try {
            return objectMapper.readValue(metadataPath.toFile(), ProjectMetadata.class);
        } catch (IOException ex) {
            throw new BusinessException("Read project metadata failed: " + projectId, ex);
        }
    }

    public void saveMetadata(ProjectMetadata metadata) {
        Path projectDir = resolveProjectDir(metadata.getId());
        try {
            Files.createDirectories(projectDir);
            objectMapper.writeValue(projectDir.resolve("metadata.json").toFile(), metadata);
        } catch (IOException ex) {
            throw new BusinessException("Save project metadata failed: " + metadata.getId(), ex);
        }
    }

    public Path resolveProjectDir(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            throw new BusinessException("Project id is required");
        }
        return storageBaseDir.resolve("projects").resolve(projectId);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Uploaded file is empty");
        }
        String originalFileName = cleanOriginalFileName(file.getOriginalFilename());
        if (!StringUtils.hasText(originalFileName)) {
            throw new BusinessException("Uploaded file name is required");
        }
        String fileType = getExtension(originalFileName);
        if (!"docx".equals(fileType)) {
            throw new BusinessException("Only docx requirement files are supported");
        }
    }

    private void validateSplitExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Uploaded Excel is empty");
        }
        String originalFileName = cleanOriginalFileName(file.getOriginalFilename());
        if (!StringUtils.hasText(originalFileName)) {
            throw new BusinessException("Uploaded Excel file name is required");
        }
        String fileType = getExtension(originalFileName);
        if (!"xlsx".equals(fileType) && !"xls".equals(fileType)) {
            throw new BusinessException("Only xlsx or xls split Excel files are supported");
        }
    }

    private String generateProjectId() {
        String timePart = LocalDateTime.now().format(ID_TIME_FORMATTER);
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        return "P" + timePart + randomPart;
    }

    private String resolveProjectName(String projectName, String originalFileName) {
        if (StringUtils.hasText(projectName)) {
            return projectName.trim();
        }
        int dotIndex = originalFileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return originalFileName.substring(0, dotIndex);
        }
        return originalFileName;
    }

    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String cleanOriginalFileName(String originalFileName) {
        if (!StringUtils.hasText(originalFileName)) {
            return "";
        }
        return StringUtils.cleanPath(originalFileName);
    }
}
