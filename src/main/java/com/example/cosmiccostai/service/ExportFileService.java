package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.ExportFileActionResponse;
import com.example.cosmiccostai.model.ProjectMetadata;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class ExportFileService {

    private final FileStorageService fileStorageService;

    public ExportFileService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    public Resource download(String projectId, String fileType) {
        Path path = resolveExportPath(projectId, fileType);
        return new FileSystemResource(path.toFile());
    }

    public String fileName(String projectId, String fileType) {
        return resolveExportPath(projectId, fileType).getFileName().toString();
    }

    public ExportFileActionResponse openDirectory(String projectId, String fileType) {
        Path path = resolveExportPath(projectId, fileType);
        ExportFileActionResponse response = buildResponse(projectId, fileType, path);
        if (!isWindows()) {
            response.setOpened(false);
            response.setMessage("当前不是 Windows 环境，请使用下载链接。");
            return response;
        }
        try {
            Path directory = path.toAbsolutePath().normalize().getParent();
            new ProcessBuilder("explorer.exe", directory.toString()).start();
            response.setOpened(true);
            response.setMessage("已打开输出目录：" + directory);
            return response;
        } catch (IOException ex) {
            throw new BusinessException("打开输出目录失败：" + path.toAbsolutePath().normalize().getParent(), ex);
        }
    }

    public ExportFileActionResponse info(String projectId, String fileType) {
        return buildResponse(projectId, fileType, resolveExportPath(projectId, fileType));
    }

    private ExportFileActionResponse buildResponse(String projectId, String fileType, Path path) {
        ExportFileActionResponse response = new ExportFileActionResponse();
        response.setProjectId(projectId);
        response.setFileType(fileType);
        response.setFilePath(path.toString());
        response.setDownloadUrl("/api/projects/" + projectId + "/exports/" + fileType + "/download");
        response.setWindows(isWindows());
        response.setOpened(false);
        response.setMessage(isWindows() ? "Windows 环境可打开输出目录。" : "非 Windows 环境请下载文件。");
        return response;
    }

    private Path resolveExportPath(String projectId, String fileType) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        String path;
        if ("excel".equalsIgnoreCase(fileType)) {
            path = metadata.getExcelOutputPath();
        } else if ("word".equalsIgnoreCase(fileType)) {
            path = metadata.getWordOutputPath();
        } else {
            throw new BusinessException("Unsupported export file type: " + fileType);
        }
        if (!StringUtils.hasText(path)) {
            throw new BusinessException(fileType + " has not been exported yet");
        }
        Path exportPath = Paths.get(path).toAbsolutePath().normalize();
        if (!Files.exists(exportPath)) {
            throw new BusinessException("Export file not found: " + exportPath);
        }
        return exportPath;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
