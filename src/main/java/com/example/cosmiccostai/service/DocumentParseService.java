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
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final FileStorageService fileStorageService;
    private final RequirementInputService requirementInputService;

    @Autowired
    public DocumentParseService(FileStorageService fileStorageService, RequirementInputService requirementInputService) {
        this.fileStorageService = fileStorageService;
        this.requirementInputService = requirementInputService;
    }

    DocumentParseService(FileStorageService fileStorageService) {
        this(fileStorageService, new RequirementInputService(fileStorageService));
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
        requirementInputService.clearCustomization(projectId);
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
        response.setRequirementDetailLength(requirementDetail.length());
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
            DocxParseCounter counter = new DocxParseCounter();
            appendDocxBodyElements(text, document.getBodyElements(), counter);
            paragraphCount = counter.paragraphCount;
            tableCount = counter.tableCount;
        } catch (IOException ex) {
            throw new BusinessException("解析 docx 文件失败，请确认文件是由 Word/WPS 正常另存为的 .docx 文件。", ex);
        }

        return new ParsedDocument(text.toString().trim(), paragraphCount, tableCount);
    }

    /**
     * Word often mixes ordinary paragraphs and tables within one requirement section.
     * Reading paragraphs and tables in two separate passes changes their original order,
     * which can make a later heading look like it belongs before its functional description.
     */
    private void appendDocxBodyElements(StringBuilder text, Iterable<IBodyElement> elements, DocxParseCounter counter) {
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph) {
                String line = normalizeLine(((XWPFParagraph) element).getText());
                if (StringUtils.hasText(line)) {
                    text.append(line).append(System.lineSeparator());
                    counter.paragraphCount++;
                }
                continue;
            }
            if (element instanceof XWPFTable) {
                appendDocxTable(text, (XWPFTable) element, counter);
            }
        }
    }

    private void appendDocxTable(StringBuilder text, XWPFTable table, DocxParseCounter counter) {
        counter.tableCount++;
        text.append(System.lineSeparator())
                .append("[TABLE ")
                .append(counter.tableCount)
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
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }

    private String extractRequirementDetailPreview(String documentText) {
        return RequirementSectionExtractor.extract(documentText);
    }

    private RequirementDiagnosis diagnoseRequirementSection(String requirementDetail) {
        if (!StringUtils.hasText(requirementDetail)) {
            return new RequirementDiagnosis(false,
                "未识别到有效的需求内容。",
                "请在需求正文前增加清晰大标题，例如“需求内容”“需求细则”“业务功能”或“功能描述”，并将具体业务规则放在该标题之后。");
        }
        return new RequirementDiagnosis(true, "", "");
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

    private static class DocxParseCounter {
        private int paragraphCount;
        private int tableCount;
    }
}
