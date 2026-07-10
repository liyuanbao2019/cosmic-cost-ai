package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.ExportFileActionResponse;
import com.example.cosmiccostai.model.ProjectMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialArchiveServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsZipNamedAfterWordAndContainsAllThreeMaterials() throws IOException {
        FileStorageService storageService = new FileStorageService(tempDir.resolve("storage").toString());
        ProjectMetadata metadata = metadata("PARCHIVE001");
        Path output = tempDir.resolve("output").resolve(metadata.getId());
        Files.createDirectories(output);
        Path excel = write(output.resolve("附件3-功能点拆分表.xlsx"), "excel");
        Path business = write(output.resolve("附件2-业务需求汇总表.xlsx"), "business");
        Path word = write(output.resolve("附件1-测试项目需求说明书.docx"), "word");
        metadata.setExcelOutputPath(excel.toString());
        metadata.setBusinessRequirementOutputPath(business.toString());
        metadata.setWordOutputPath(word.toString());
        storageService.saveMetadata(metadata);

        MaterialArchiveService service = new MaterialArchiveService(storageService, new ExportFileService(storageService));
        ExportFileActionResponse response = service.export(metadata.getId());

        Path archive = Paths.get(response.getFilePath());
        assertEquals("附件1-测试项目需求说明书.zip", archive.getFileName().toString());
        assertEquals("archive", response.getFileType());
        assertTrue(Files.isRegularFile(archive));
        assertEquals(new HashSet<String>(Arrays.asList(excel.getFileName().toString(),
                        business.getFileName().toString(), word.getFileName().toString())),
                zipEntryNames(archive));
        assertEquals(archive.toString(), storageService.loadMetadata(metadata.getId()).getMaterialArchiveOutputPath());
    }

    @Test
    void refusesToCreateIncompleteArchive() {
        FileStorageService storageService = new FileStorageService(tempDir.resolve("storage-missing").toString());
        ProjectMetadata metadata = metadata("PARCHIVE002");
        storageService.saveMetadata(metadata);
        MaterialArchiveService service = new MaterialArchiveService(storageService, new ExportFileService(storageService));

        assertThrows(BusinessException.class, () -> service.export(metadata.getId()));
    }

    private ProjectMetadata metadata(String projectId) {
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(projectId);
        metadata.setName("测试项目");
        metadata.setStatus("WORD_EXPORTED");
        return metadata;
    }

    private Path write(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private Set<String> zipEntryNames(Path archive) throws IOException {
        Set<String> names = new HashSet<String>();
        try (InputStream inputStream = Files.newInputStream(archive);
             ZipInputStream zipInputStream = new ZipInputStream(inputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
