package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.ParseResponse;
import com.example.cosmiccostai.model.ProjectMetadata;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class DocumentParseService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int PREVIEW_MAX_LENGTH = 300;

    private final FileStorageService fileStorageService;

    public DocumentParseService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    public ParseResponse parseProject(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);

        Path sourceFile = Paths.get(metadata.getStoredFilePath());
        if (!Files.exists(sourceFile)) {
            throw new BusinessException("Source file not found: " + sourceFile);
        }

        ParsedDocument parsedDocument = parseDocx(sourceFile);
        Path textPath = fileStorageService.resolveProjectDir(projectId).resolve("document-text.txt");
        try {
            Files.write(textPath, parsedDocument.getText().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BusinessException("Write parsed text failed: " + projectId, ex);
        }

        metadata.setDocumentTextPath(textPath.toString());
        metadata.setStatus("PARSED");
        metadata.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
        fileStorageService.saveMetadata(metadata);

        ParseResponse response = new ParseResponse();
        response.setProjectId(projectId);
        response.setSourceFileName(metadata.getSourceFileName());
        response.setDocumentTextPath(textPath.toString());
        response.setParagraphCount(parsedDocument.getParagraphCount());
        response.setTableCount(parsedDocument.getTableCount());
        response.setTextLength(parsedDocument.getText().length());
        response.setStatus(metadata.getStatus());
        response.setPreview(buildPreview(parsedDocument.getText()));
        return response;
    }

    private void validateMetadata(ProjectMetadata metadata) {
        if (metadata == null) {
            throw new BusinessException("Project metadata is empty");
        }
        if (!StringUtils.hasText(metadata.getSourceFileName())) {
            throw new BusinessException("Source file name is required");
        }
        if (!"docx".equalsIgnoreCase(metadata.getFileType())) {
            throw new BusinessException("Only docx requirement files can be parsed");
        }
    }

    private ParsedDocument parseDocx(Path sourceFile) {
        StringBuilder text = new StringBuilder();
        int paragraphCount = 0;
        int tableCount = 0;

        try (InputStream inputStream = Files.newInputStream(sourceFile);
             XWPFDocument document = new XWPFDocument(inputStream)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String line = normalizeLine(paragraph.getText());
                if (StringUtils.hasText(line)) {
                    text.append(line).append(System.lineSeparator());
                    paragraphCount++;
                }
            }

            for (XWPFTable table : document.getTables()) {
                tableCount++;
                text.append(System.lineSeparator())
                        .append("[TABLE ")
                        .append(tableCount)
                        .append("]")
                        .append(System.lineSeparator());
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder rowText = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        if (rowText.length() > 0) {
                            rowText.append(" | ");
                        }
                        rowText.append(normalizeLine(cell.getText()));
                    }
                    if (StringUtils.hasText(rowText.toString())) {
                        text.append(rowText).append(System.lineSeparator());
                    }
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("Parse docx failed: " + sourceFile, ex);
        }

        return new ParsedDocument(text.toString().trim(), paragraphCount, tableCount);
    }

    private String normalizeLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00A0', ' ')
                .replace('\t', ' ')
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll(" +", " ")
                .trim();
    }

    private String buildPreview(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("[\\r\\n]+", " ").trim();
        if (compact.length() <= PREVIEW_MAX_LENGTH) {
            return compact;
        }
        return compact.substring(0, PREVIEW_MAX_LENGTH);
    }

    private static class ParsedDocument {

        private final String text;
        private final int paragraphCount;
        private final int tableCount;

        private ParsedDocument(String text, int paragraphCount, int tableCount) {
            this.text = text;
            this.paragraphCount = paragraphCount;
            this.tableCount = tableCount;
        }

        public String getText() {
            return text;
        }

        public int getParagraphCount() {
            return paragraphCount;
        }

        public int getTableCount() {
            return tableCount;
        }
    }
}
