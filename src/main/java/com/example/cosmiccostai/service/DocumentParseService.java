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
        response.setPreview(buildPreview(extractRequirementDetailPreview(parsedDocument.getText())));
        RequirementDiagnosis diagnosis = diagnoseRequirementSection(parsedDocument.getText());
        response.setRequirementRecognized(diagnosis.recognized);
        response.setRequirementWarning(diagnosis.warning);
        response.setRequirementSuggestion(diagnosis.suggestion);
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

    private String extractRequirementDetailPreview(String documentText) {
        if (!StringUtils.hasText(documentText)) {
            return "";
        }
        String[] lines = documentText.split("\\r?\\n");
        StringBuilder detail = new StringBuilder();
        boolean collecting = false;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (!collecting) {
                if (isRequirementSection(line)) {
                    collecting = true;
                }
                continue;
            }

            if (isSectionBoundary(line)) {
                break;
            }
            if (StringUtils.hasText(line)) {
                detail.append(line).append(System.lineSeparator());
            }
        }

        String extracted = detail.toString().trim();
        if (StringUtils.hasText(extracted)) {
            return extracted;
        }
        return fallbackRequirementDetailPreview(lines);
    }

    private boolean isSectionBoundary(String line) {
        String normalized = normalizeSectionTitle(line);
        if (!StringUtils.hasText(normalized) || isRequirementSection(line)) {
            return false;
        }
        return "非功能性需求".equals(normalized)
                || "非功能需求".equals(normalized)
                || "附加值调整因子说明".equals(normalized)
                || "系统现状".equals(normalized)
                || "功能架构图".equals(normalized)
                || "总体描述".equals(normalized)
                || "建设目标".equals(normalized)
                || "建设必要性".equals(normalized)
                || "背景及目标".equals(normalized)
                || "归属系统".equals(normalized);
    }

    private String fallbackRequirementDetailPreview(String[] lines) {
        StringBuilder builder = new StringBuilder();
        boolean passedFrontMatter = false;
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (!StringUtils.hasText(line) || isCatalogLine(line)) {
                continue;
            }
            String title = normalizeSectionTitle(line);
            if ("背景及目标".equals(title) || "归属系统".equals(title) || "系统现状".equals(title)
                    || "总体描述".equals(title) || "建设目标".equals(title) || "建设必要性".equals(title)) {
                passedFrontMatter = true;
                continue;
            }
            if (!passedFrontMatter && looksLikeBusinessRequirement(line)) {
                passedFrontMatter = true;
            }
            if (passedFrontMatter && !isSectionBoundary(line) && !looksLikeOnlyTitle(line)) {
                builder.append(line).append(System.lineSeparator());
            }
        }
        return builder.toString().trim();
    }

    private boolean isCatalogLine(String line) {
        return line.contains("......") || line.matches(".*\\.{3,}\\s*\\d+\\s*$");
    }

    private boolean looksLikeOnlyTitle(String line) {
        String title = normalizeSectionTitle(line);
        return title.length() <= 12 && !containsAny(line, "新增", "优化", "变更", "修改", "删除", "查询", "统计", "保存", "更新", "按照", "需要", "实现");
    }

    private RequirementDiagnosis diagnoseRequirementSection(String text) {
        if (!StringUtils.hasText(text)) {
            return new RequirementDiagnosis(false,
                    "未解析到有效文档正文。",
                    "请确认上传的是包含正文内容的 docx 文件，且需求内容不是纯图片。");
        }
        String[] lines = text.split("\\r?\\n");
        for (String rawLine : lines) {
            if (isRequirementSection(rawLine)) {
                return new RequirementDiagnosis(true, "", "");
            }
        }
        for (String rawLine : lines) {
            if (looksLikeBusinessRequirement(rawLine)) {
                return new RequirementDiagnosis(false,
                        "未识别到标准的需求章节标题，系统将尝试从疑似需求段落中提取内容。",
                        "建议在需求正文前增加清晰标题，例如“需求细则：”“需求内容：”或“需求规则：”，并将具体业务规则放在该标题之后。");
            }
        }
        return new RequirementDiagnosis(false,
                "未识别到需求细则、需求内容或明显的业务规则描述。",
                "请在需求文档中增加“需求细则：”或“需求内容：”标识，并在其下填写新增、修改、查询、统计、保存等具体业务需求。");
    }

    private boolean isRequirementSection(String line) {
        String normalized = normalizeSectionTitle(line);
        return "需求细则".equals(normalized)
                || "需求内容".equals(normalized)
                || "需求规则".equals(normalized)
                || "需求明细".equals(normalized)
                || "业务需求".equals(normalized)
                || "功能需求".equals(normalized)
                || "功能需求说明".equals(normalized);
    }

    private String normalizeSectionTitle(String line) {
        if (!StringUtils.hasText(line)) {
            return "";
        }
        return line.trim()
                .replaceAll("\\s+", "")
                .replaceAll("^[一二三四五六七八九十]+[、.．,，]?+", "")
                .replaceAll("^\\(?\\d+\\)?[、.．,，]?+", "")
                .replaceAll("^第[一二三四五六七八九十\\d]+[章节条][、.．,，]?+", "")
                .replaceAll("[:：]+$", "")
                .trim();
    }

    private boolean looksLikeBusinessRequirement(String line) {
        return containsAny(line, "新增", "优化", "变更", "修改", "删除", "查询", "统计", "保存", "更新", "按照", "需要", "实现", "规则", "数据", "字段");
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
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

    private static class RequirementDiagnosis {

        private final boolean recognized;
        private final String warning;
        private final String suggestion;

        private RequirementDiagnosis(boolean recognized, String warning, String suggestion) {
            this.recognized = recognized;
            this.warning = warning;
            this.suggestion = suggestion;
        }
    }
}
