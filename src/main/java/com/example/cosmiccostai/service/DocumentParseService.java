package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.ParseResponse;
import com.example.cosmiccostai.model.ProjectMetadata;
import org.apache.poi.EmptyFileException;
import org.apache.poi.OldFileFormatException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.OfficeXmlFileException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
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
    private static final int PREVIEW_MAX_LENGTH = 12000;

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

        ParsedDocument parsedDocument = parseWordDocument(sourceFile);
        Path textPath = fileStorageService.resolveProjectDir(projectId).resolve("document-text.txt");
        try {
            Files.write(textPath, parsedDocument.getText().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BusinessException("Write parsed text failed: " + projectId, ex);
        }

        String requirementDetail = extractRequirementDetailPreview(parsedDocument.getText());
        Path requirementDetailPath = fileStorageService.resolveProjectDir(projectId).resolve("requirement-detail.txt");
        try {
            Files.write(requirementDetailPath, requirementDetail.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BusinessException("Write requirement detail failed: " + projectId, ex);
        }

        metadata.setDocumentTextPath(textPath.toString());
        metadata.setRequirementDetailPath(requirementDetailPath.toString());
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
        response.setPreview(buildPreview(requirementDetail));
        RequirementDiagnosis diagnosis = diagnoseRequirementSection(requirementDetail);
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
        if (!"docx".equalsIgnoreCase(metadata.getFileType()) && !"doc".equalsIgnoreCase(metadata.getFileType())) {
            throw new BusinessException("Only doc or docx requirement files can be parsed");
        }
    }

    private ParsedDocument parseWordDocument(Path sourceFile) {
        try (InputStream inputStream = FileMagic.prepareToCheckMagic(Files.newInputStream(sourceFile))) {
            FileMagic fileMagic = FileMagic.valueOf(inputStream);
            if (FileMagic.OOXML.equals(fileMagic)) {
                return parseDocx(inputStream, sourceFile);
            }
            if (FileMagic.OLE2.equals(fileMagic)) {
                return parseDoc(inputStream, sourceFile);
            }
            throw new BusinessException("无法识别 Word 文件格式，请上传真正的 .doc 或 .docx 文件，不要仅手动修改文件后缀。");
        } catch (BusinessException ex) {
            throw ex;
        } catch (EmptyFileException ex) {
            throw new BusinessException("上传的 Word 文件为空，请重新选择文件。", ex);
        } catch (IOException ex) {
            throw new BusinessException("读取 Word 文件失败，请确认文件未损坏或未被其他程序占用。", ex);
        }
    }

    private ParsedDocument parseDocx(InputStream inputStream, Path sourceFile) {
        StringBuilder text = new StringBuilder();
        int paragraphCount = 0;
        int tableCount = 0;

        try (XWPFDocument document = new XWPFDocument(inputStream)) {
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
            throw new BusinessException("解析 docx 文件失败，请确认文件是由 Word/WPS 正常另存为的 .docx 文件。", ex);
        }

        return new ParsedDocument(text.toString().trim(), paragraphCount, tableCount);
    }

    private ParsedDocument parseDoc(InputStream inputStream, Path sourceFile) {
        StringBuilder text = new StringBuilder();
        int paragraphCount = 0;

        try (POIFSFileSystem fileSystem = new POIFSFileSystem(inputStream);
             HWPFDocument document = new HWPFDocument(fileSystem);
             WordExtractor extractor = new WordExtractor(document)) {
            String[] paragraphs = extractor.getParagraphText();
            if (paragraphs != null) {
                for (String paragraph : paragraphs) {
                    String line = normalizeLine(paragraph);
                    if (StringUtils.hasText(line)) {
                        text.append(line).append(System.lineSeparator());
                        paragraphCount++;
                    }
                }
            }
            if (!StringUtils.hasText(text.toString())) {
                String bodyText = normalizeLine(extractor.getText());
                if (StringUtils.hasText(bodyText)) {
                    text.append(bodyText).append(System.lineSeparator());
                    paragraphCount = 1;
                }
            }
        } catch (OfficeXmlFileException ex) {
            throw new BusinessException("文件内容是 docx 格式，请使用 .docx 后缀重新上传。", ex);
        } catch (OldFileFormatException ex) {
            throw new BusinessException("该 doc 文件版本过旧，暂不支持直接解析。请用 Word/WPS 打开后另存为 .docx 再上传。", ex);
        } catch (IOException | RuntimeException ex) {
            throw new BusinessException("解析 doc 文件失败，请用 Word/WPS 打开后另存为 .docx 再上传。", ex);
        }

        if (!StringUtils.hasText(text.toString())) {
            throw new BusinessException("未从 doc 文件中读取到有效文本，请确认文档内容不是图片扫描件。");
        }
        return new ParsedDocument(text.toString().trim(), paragraphCount, 0);
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
        String preview = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (preview.length() <= PREVIEW_MAX_LENGTH) {
            return preview;
        }
        return preview.substring(0, PREVIEW_MAX_LENGTH) + System.lineSeparator() + "...（内容较长，已截断）";
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

        return refineRequirementDetail(detail.toString());
    }

    private String refineRequirementDetail(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (!StringUtils.hasText(trimmed)) {
            return "";
        }
        String[] lines = trimmed.split("\\r?\\n");
        StringBuilder functionDescription = new StringBuilder();
        boolean collectingFunctionDescription = false;
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (!collectingFunctionDescription) {
                if (isFunctionDescriptionSection(line)) {
                    collectingFunctionDescription = true;
                }
                continue;
            }
            if (isSectionBoundary(line)) {
                break;
            }
            if (StringUtils.hasText(line)) {
                functionDescription.append(line).append(System.lineSeparator());
            }
        }
        if (StringUtils.hasText(functionDescription.toString())) {
            return functionDescription.toString().trim();
        }

        StringBuilder bounded = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (bounded.length() > 0 && isSectionBoundary(line)) {
                break;
            }
            if (StringUtils.hasText(line)) {
                bounded.append(line).append(System.lineSeparator());
            }
        }
        return bounded.toString().trim();
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
                || "功能界面".equals(normalized)
                || "流程图".equals(normalized)
                || "调整因子情况说明".equals(normalized)
                || isTopLevelSectionHeading(line);
    }

    private RequirementDiagnosis diagnoseRequirementSection(String requirementDetail) {
        if (!StringUtils.hasText(requirementDetail)) {
            return new RequirementDiagnosis(false,
                "未识别到有效的需求内容。",
                "请在需求正文前增加清晰大标题，例如“需求内容”“需求细则”“业务功能”或“功能描述”，并将具体业务规则放在该标题之后。");
        }
        return new RequirementDiagnosis(true, "", "");
    }

    private boolean isRequirementSection(String line) {
        String normalized = normalizeSectionTitle(line);
        return "需求内容".equals(normalized)
                || "需求细则".equals(normalized)
                || "业务功能".equals(normalized)
                || "功能需求".equals(normalized)
                || "功能性需求".equals(normalized)
                || isFunctionDescriptionSection(line);
    }

    private boolean isFunctionDescriptionSection(String line) {
        String normalized = normalizeSectionTitle(line);
        return "功能描述".equals(normalized)
                || "需求描述".equals(normalized);
    }

    private String normalizeSectionTitle(String line) {
        if (!StringUtils.hasText(line)) {
            return "";
        }
        return line.trim()
                .replaceAll("\\s+", "")
                .replaceAll("^\\d+(?:[.．]\\d+)+[、.．,，]?+", "")
                .replaceAll("^[一二三四五六七八九十]+[、.．,，]?+", "")
                .replaceAll("^\\(?\\d+\\)?[、.．,，]?+", "")
                .replaceAll("^第[一二三四五六七八九十\\d]+[章节条][、.．,，]?+", "")
                .replaceAll("[:：]+$", "")
                .trim();
    }

    private boolean isTopLevelSectionHeading(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        String value = line.trim();
        if (value.startsWith("（") || value.startsWith("(") || value.matches("^\\d+\\.\\d+.*")
                || value.matches("^\\d+[\\.．、].*")) {
            return false;
        }
        return value.matches("^[一二三四五六七八九十]+[、.．]\\s*\\S.{0,60}$")
                || value.matches("^第[一二三四五六七八九十\\d]+[章节条]\\s*\\S.{0,60}$")
                || value.matches("^\\d+[、.．]\\s*\\D.{0,60}$");
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
