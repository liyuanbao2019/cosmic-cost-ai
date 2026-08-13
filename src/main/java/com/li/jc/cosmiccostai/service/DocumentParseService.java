package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.ParseResponse;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.apache.poi.EmptyFileException;
import org.apache.poi.OldFileFormatException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.OfficeXmlFileException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
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
import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentParseService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object DOCX_PARSE_LOCK = new Object();
    private static final double DOCX_MIN_INFLATE_RATIO = 0.005D;
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
            writeDocumentOutline(fileStorageService.resolveProjectDir(projectId).resolve("document-outline.tsv"),
                    parsedDocument.getHeadings());
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
        RequirementDiagnosis diagnosis = diagnoseRequirementSection(requirementDetail, parsedDocument.getHeadings());
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
        // Word/WPS may embed highly-compressible fonts whose ratio is only
        // slightly below POI's default 1% threshold. Use a still-bounded 0.5%
        // threshold while parsing, then restore POI's global value immediately.
        synchronized (DOCX_PARSE_LOCK) {
            double originalMinInflateRatio = ZipSecureFile.getMinInflateRatio();
            ZipSecureFile.setMinInflateRatio(Math.min(originalMinInflateRatio, DOCX_MIN_INFLATE_RATIO));
            try (XWPFDocument document = new XWPFDocument(inputStream)) {
                StringBuilder text = new StringBuilder();
                DocxParseCounter counter = new DocxParseCounter();
                appendDocxBodyElements(text, document.getBodyElements(), document.getStyles(), counter);
                return new ParsedDocument(text.toString().trim(), counter.paragraphCount,
                        counter.tableCount, counter.headings);
            } catch (IOException ex) {
                throw new BusinessException(docxParseFailureMessage(ex), ex);
            } finally {
                ZipSecureFile.setMinInflateRatio(originalMinInflateRatio);
            }
        }
    }

    private String docxParseFailureMessage(IOException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        if (message.contains("Zip bomb detected")) {
            return "DOCX 中存在压缩率异常的内嵌资源，系统已拒绝解析；请在 Word/WPS 中取消嵌入字体后重新保存。";
        }
        return "解析 docx 文件失败，请确认文件未损坏，并使用 Word/WPS 重新另存为 .docx。";
    }

    /**
     * Word often mixes ordinary paragraphs and tables within one requirement section.
     * Reading paragraphs and tables in two separate passes changes their original order,
     * which can make a later heading look like it belongs before its functional description.
     */
    private void appendDocxBodyElements(StringBuilder text,
                                        Iterable<IBodyElement> elements,
                                        XWPFStyles styles,
                                        DocxParseCounter counter) {
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph) {
                XWPFParagraph paragraph = (XWPFParagraph) element;
                String line = normalizeLine(paragraph.getText());
                if (StringUtils.hasText(line)) {
                    text.append(line).append(System.lineSeparator());
                    counter.paragraphCount++;
                    int headingLevel = resolveHeadingLevel(paragraph, styles);
                    if (headingLevel > 0 && headingLevel <= 9) {
                        counter.headings.add(new HeadingMarker(headingLevel, line));
                    }
                }
                continue;
            }
            if (element instanceof XWPFTable) {
                appendDocxTable(text, (XWPFTable) element, counter);
            }
        }
    }

    private int resolveHeadingLevel(XWPFParagraph paragraph, XWPFStyles styles) {
        if (paragraph.getCTP().isSetPPr()
                && paragraph.getCTP().getPPr().isSetOutlineLvl()
                && paragraph.getCTP().getPPr().getOutlineLvl().getVal() != null) {
            return paragraph.getCTP().getPPr().getOutlineLvl().getVal().intValue() + 1;
        }
        String styleId = paragraph.getStyle();
        if (!StringUtils.hasText(styleId) || styles == null) {
            return 0;
        }
        XWPFStyle style = styles.getStyle(styleId);
        if (style == null) {
            return 0;
        }
        if (style.getCTStyle().isSetPPr()
                && style.getCTStyle().getPPr().isSetOutlineLvl()
                && style.getCTStyle().getPPr().getOutlineLvl().getVal() != null) {
            return style.getCTStyle().getPPr().getOutlineLvl().getVal().intValue() + 1;
        }
        String styleName = style.getName() == null ? "" : style.getName().trim().toLowerCase();
        if (styleName.matches("heading\\s*[1-9]")) {
            return Integer.parseInt(styleName.replaceAll("\\D+", ""));
        }
        if (styleName.matches("标题\\s*[1-9]")) {
            return Integer.parseInt(styleName.replaceAll("\\D+", ""));
        }
        return 0;
    }

    private void writeDocumentOutline(Path path, List<HeadingMarker> headings) throws IOException {
        List<String> lines = new ArrayList<String>();
        for (HeadingMarker heading : headings) {
            lines.add(heading.level + "\t" + heading.title.replace('\t', ' '));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
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
        return new ParsedDocument(text.toString().trim(), paragraphCount, 0, new ArrayList<HeadingMarker>());
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
        return diagnoseRequirementSection(requirementDetail, new ArrayList<HeadingMarker>());
    }

    private RequirementDiagnosis diagnoseRequirementSection(String requirementDetail,
                                                              List<HeadingMarker> headings) {
        if (!StringUtils.hasText(requirementDetail)) {
            if (containsRecognizableRequirementHeading(headings)) {
                return new RequirementDiagnosis(true, "", "");
            }
            return new RequirementDiagnosis(false,
                "未识别到有效的需求内容。",
                "系统将展示已解析出的全部章节，请人工勾选需要送给 AI 的章节；也可使用“需求内容”“需求细则”“业务功能”“功能说明”“功能描述”或“需求方案 word 描述”等标题。");
        }
        return new RequirementDiagnosis(true, "", "");
    }

    private boolean containsRecognizableRequirementHeading(List<HeadingMarker> headings) {
        for (HeadingMarker heading : headings == null
                ? new ArrayList<HeadingMarker>() : headings) {
            String title = heading == null || heading.title == null ? "" : heading.title.trim()
                    .replaceAll("\\s+", "")
                    .replaceAll("^\\d+(?:[.．]\\d+)*[、.．]?", "");
            if ("功能说明".equals(title) || "功能描述".equals(title)
                    || "需求内容".equals(title) || "需求细则".equals(title)
                    || "业务功能".equals(title) || "业务功能需求".equals(title)
                    || "功能需求".equals(title) || "功能性需求".equals(title)
                    || "需求方案word描述".equalsIgnoreCase(title)) {
                return true;
            }
        }
        return false;
    }

    private static class ParsedDocument {

        private final String text;
        private final int paragraphCount;
        private final int tableCount;
        private final List<HeadingMarker> headings;

        private ParsedDocument(String text, int paragraphCount, int tableCount, List<HeadingMarker> headings) {
            this.text = text;
            this.paragraphCount = paragraphCount;
            this.tableCount = tableCount;
            this.headings = headings;
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

        public List<HeadingMarker> getHeadings() {
            return headings;
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
        private final List<HeadingMarker> headings = new ArrayList<HeadingMarker>();
    }

    private static class HeadingMarker {
        private final int level;
        private final String title;

        private HeadingMarker(int level, String title) {
            this.level = level;
            this.title = title;
        }
    }
}
