package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.ExportFileActionResponse;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class MaterialArchiveService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileStorageService fileStorageService;
    private final ExportFileService exportFileService;

    public MaterialArchiveService(FileStorageService fileStorageService,
                                  ExportFileService exportFileService) {
        this.fileStorageService = fileStorageService;
        this.exportFileService = exportFileService;
    }

    public ExportFileActionResponse export(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        Path excelPath = requiredFile(metadata.getExcelOutputPath(), "功能点拆分表");
        Path businessPath = requiredFile(metadata.getBusinessRequirementOutputPath(), "业务需求汇总表");
        Path wordPath = requiredFile(metadata.getWordOutputPath(), "需求说明书");

        Path outputDirectory = wordPath.toAbsolutePath().normalize().getParent();
        if (outputDirectory == null) {
            throw new BusinessException("无法确定送审材料压缩包的输出目录。");
        }
        Path archivePath = outputDirectory.resolve(archiveFileName(wordPath.getFileName().toString()));
        Path temporaryPath = outputDirectory.resolve(archivePath.getFileName().toString()
                + ".tmp-" + UUID.randomUUID().toString().replace("-", ""));

        try {
            Files.createDirectories(outputDirectory);
            writeArchive(temporaryPath, excelPath, businessPath, wordPath);
            replaceArchive(temporaryPath, archivePath);
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(temporaryPath);
            } catch (IOException cleanupEx) {
                ex.addSuppressed(cleanupEx);
            }
            throw new BusinessException("生成送审材料压缩包失败，请确认目标 ZIP 未被占用：" + archivePath, ex);
        }

        metadata.setMaterialArchiveOutputPath(archivePath.toString());
        metadata.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
        fileStorageService.saveMetadata(metadata);
        return exportFileService.info(projectId, "archive");
    }

    private void writeArchive(Path target, Path... sourceFiles) throws IOException {
        Set<String> entryNames = new HashSet<String>();
        try (OutputStream outputStream = Files.newOutputStream(target);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            for (Path sourceFile : sourceFiles) {
                String entryName = sourceFile.getFileName().toString();
                if (!entryNames.add(entryName)) {
                    throw new IOException("压缩包内存在重名文件：" + entryName);
                }
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(Files.getLastModifiedTime(sourceFile).toMillis());
                zipOutputStream.putNextEntry(entry);
                Files.copy(sourceFile, zipOutputStream);
                zipOutputStream.closeEntry();
            }
        }
    }

    private void replaceArchive(Path temporaryPath, Path archivePath) throws IOException {
        try {
            Files.move(temporaryPath, archivePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporaryPath, archivePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path requiredFile(String path, String label) {
        if (!StringUtils.hasText(path)) {
            throw new BusinessException(label + "尚未导出，不能生成送审材料压缩包。");
        }
        Path resolved = Paths.get(path).toAbsolutePath().normalize();
        if (!Files.isRegularFile(resolved)) {
            throw new BusinessException(label + "文件不存在，不能生成送审材料压缩包：" + resolved);
        }
        return resolved;
    }

    String archiveFileName(String wordFileName) {
        int dotIndex = wordFileName == null ? -1 : wordFileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? wordFileName.substring(0, dotIndex) : wordFileName;
        if (!StringUtils.hasText(baseName)) {
            baseName = "送审材料";
        }
        return baseName + ".zip";
    }
}
