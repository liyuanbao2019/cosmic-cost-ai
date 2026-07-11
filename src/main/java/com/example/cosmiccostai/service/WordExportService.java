package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.WordExportResponse;
import com.example.cosmiccostai.model.CosmicResult;
import com.example.cosmiccostai.model.FunctionSplitRow;
import com.example.cosmiccostai.model.ProjectMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.xmlbeans.XmlCursor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WordExportService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String BODY_TEXT_STYLE = "2";
    private static final String SUBSECTION_HEADING_STYLE = BODY_TEXT_STYLE;
    private static final String PROCESS_HEADING_STYLE = "9";

    private final FileStorageService fileStorageService;
    private final FunctionDescriptionAiService functionDescriptionAiService;
    private final ExportProgressService exportProgressService;
    private final ObjectMapper objectMapper;
    private final Path outputBaseDir;
    private final String configuredTemplatePath;

    public WordExportService(FileStorageService fileStorageService,
                             @Value("${cosmic.output.base-dir:output}") String outputBaseDir,
                             @Value("${cosmic.template.word-path:}") String configuredTemplatePath) {
        this(fileStorageService, outputBaseDir, configuredTemplatePath, new FunctionDescriptionAiService(), new ExportProgressService());
    }

    @Autowired
    public WordExportService(FileStorageService fileStorageService,
                             @Value("${cosmic.output.base-dir:output}") String outputBaseDir,
                             @Value("${cosmic.template.word-path:}") String configuredTemplatePath,
                             FunctionDescriptionAiService functionDescriptionAiService,
                             ExportProgressService exportProgressService) {
        this.fileStorageService = fileStorageService;
        this.functionDescriptionAiService = functionDescriptionAiService;
        this.exportProgressService = exportProgressService;
        this.objectMapper = new ObjectMapper();
        this.outputBaseDir = Paths.get(outputBaseDir);
        this.configuredTemplatePath = configuredTemplatePath;
    }

    public WordExportResponse export(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);

        List<CosmicResult> results = readCosmicResults(Paths.get(metadata.getCosmicResultsPath()));
        List<FunctionSplitRow> rows = readReviewedRows(metadata);
        List<FunctionGroup> groups = groupRows(rows);
        applyExportFunctionDescriptions(metadata, groups);
        double totalCfp = calculateTotalCfp(results);

        Path projectOutputDir = outputBaseDir.resolve(projectId);
        Path outputPath = projectOutputDir.resolve(buildWordOutputFileName(metadata));
        Path actualOutputPath = outputPath;

        try {
            Files.createDirectories(projectOutputDir);
            actualOutputPath = writableOutputPath(outputPath);
            XWPFDocument document = createDocument();
            try (OutputStream outputStream = Files.newOutputStream(actualOutputPath)) {
                replaceCoverTitle(document, metadata);
                replaceFunctionRequirementSection(document, groups);
                document.write(outputStream);
            } finally {
                document.close();
            }
        } catch (IOException ex) {
            throw new BusinessException("Export Word failed: " + ex.getClass().getSimpleName() + " " + ex.getMessage(), ex);
        }

        metadata.setWordOutputPath(actualOutputPath.toString());
        metadata.setMaterialArchiveOutputPath(null);
        metadata.setStatus("WORD_EXPORTED");
        metadata.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
        fileStorageService.saveMetadata(metadata);

        WordExportResponse response = new WordExportResponse();
        response.setProjectId(projectId);
        response.setWordOutputPath(actualOutputPath.toString());
        response.setFunctionCount(groups.size());
        response.setTotalCfp(totalCfp);
        response.setStatus(metadata.getStatus());
        return response;
    }

    private void validateMetadata(ProjectMetadata metadata) {
        if (metadata == null) {
            throw new BusinessException("Project metadata is empty");
        }
        if (!StringUtils.hasText(metadata.getCosmicResultsPath())) {
            throw new BusinessException("Project has not completed COSMIC analysis yet");
        }
        if (!StringUtils.hasText(metadata.getExcelOutputPath())) {
            throw new BusinessException("Please export Excel before exporting Word");
        }
        if (!Files.exists(Paths.get(metadata.getExcelOutputPath()))) {
            throw new BusinessException("Excel output file not found: " + metadata.getExcelOutputPath());
        }
    }

    private XWPFDocument createDocument() throws IOException {
        Path templatePath = resolveTemplatePath();
        if (templatePath != null && Files.exists(templatePath)) {
            try (InputStream inputStream = Files.newInputStream(templatePath)) {
                return new XWPFDocument(inputStream);
            }
        }
        return new XWPFDocument();
    }

    private void replaceCoverTitle(XWPFDocument document, ProjectMetadata metadata) {
        String title = sourceBaseName(metadata);
        for (int i = 0; i < document.getParagraphs().size() && i < 10; i++) {
            XWPFParagraph paragraph = document.getParagraphs().get(i);
            String text = paragraph.getText() == null ? "" : paragraph.getText().trim();
            if (zh("\u0058\u0058\u9879\u76ee\u9700\u6c42\u8bf4\u660e\u4e66").equals(text)) {
                replaceParagraphText(paragraph, title);
                continue;
            }
            if (zh("\u0058\u0058\u0058\u0058\u5e74\u0058\u0058\u6708").equals(text)) {
                YearMonth now = YearMonth.now();
                replaceParagraphText(paragraph, now.getYear() + zh("\u5e74") + now.getMonthValue() + zh("\u6708"));
            }
        }
    }

    private void replaceParagraphText(XWPFParagraph paragraph, String text) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) {
            writeRun(paragraph, text, false);
            return;
        }
        XWPFRun first = runs.get(0);
        first.setText(text == null ? "" : text, 0);
        for (int i = runs.size() - 1; i >= 1; i--) {
            paragraph.removeRun(i);
        }
    }

    private List<FunctionSplitRow> readReviewedRows(ProjectMetadata metadata) {
        if (StringUtils.hasText(metadata.getFunctionSplitRowsPath()) && Files.exists(Paths.get(metadata.getFunctionSplitRowsPath()))) {
            try {
                return objectMapper.readValue(Paths.get(metadata.getFunctionSplitRowsPath()).toFile(),
                        new TypeReference<List<FunctionSplitRow>>() {
                        });
            } catch (IOException ex) {
                throw new BusinessException("Read function split rows failed", ex);
            }
        }
        return readRowsFromExcel(Paths.get(metadata.getExcelOutputPath()));
    }

    private List<FunctionSplitRow> readRowsFromExcel(Path excelPath) {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        DataFormatter formatter = new DataFormatter();
        try (InputStream inputStream = Files.newInputStream(excelPath);
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Sheet sheet = workbook.getSheet(zh("\u0032\u3001\u529f\u80fd\u70b9\u62c6\u5206\u8868"));
            if (sheet == null) {
                sheet = workbook.getSheetAt(2);
            }

            String currentProject = "";
            String currentLevel1 = "";
            String currentLevel2 = "";
            String currentLevel3 = "";
            String currentUser = "";
            String currentTrigger = "";
            String currentProcess = "";
            String currentDescription = "";
            for (int rowIndex = 5; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                currentProject = carry(currentProject, cellText(row.getCell(0), formatter, evaluator));
                currentLevel1 = carry(currentLevel1, cellText(row.getCell(1), formatter, evaluator));
                currentLevel2 = carry(currentLevel2, cellText(row.getCell(2), formatter, evaluator));
                currentLevel3 = carry(currentLevel3, cellText(row.getCell(3), formatter, evaluator));
                currentUser = carry(currentUser, cellText(row.getCell(4), formatter, evaluator));
                currentTrigger = carry(currentTrigger, cellText(row.getCell(5), formatter, evaluator));
                currentProcess = carry(currentProcess, cellText(row.getCell(6), formatter, evaluator));
                currentDescription = carry(currentDescription, cellText(row.getCell(13), formatter, evaluator));

                String subProcess = cellText(row.getCell(7), formatter, evaluator);
                String movementType = cellText(row.getCell(8), formatter, evaluator);
                if (!StringUtils.hasText(currentProcess) || (!StringUtils.hasText(subProcess) && !StringUtils.hasText(movementType))) {
                    continue;
                }
                FunctionSplitRow splitRow = new FunctionSplitRow();
                splitRow.setProjectName(currentProject);
                splitRow.setLevel1Module(currentLevel1);
                splitRow.setLevel2Module(StringUtils.hasText(currentLevel2) ? currentLevel2 : currentLevel1);
                splitRow.setLevel3Module(StringUtils.hasText(currentLevel3) ? currentLevel3 : splitRow.getLevel2Module());
                splitRow.setFunctionalUser(currentUser);
                splitRow.setTriggerEvent(currentTrigger);
                splitRow.setFunctionProcess(currentProcess);
                splitRow.setFunctionDescription(currentDescription);
                splitRow.setSubProcessDescription(subProcess);
                splitRow.setMovementType(movementType);
                splitRow.setDataGroup(cellText(row.getCell(9), formatter, evaluator));
                splitRow.setDataAttributes(cellText(row.getCell(10), formatter, evaluator));
                splitRow.setReuseDegree(cellText(row.getCell(11), formatter, evaluator));
                splitRow.setSequenceDiagram(cellText(row.getCell(14), formatter, evaluator));
                rows.add(splitRow);
            }
        } catch (IOException ex) {
            throw new BusinessException("Read Excel for Word export failed: " + excelPath, ex);
        }
        if (rows.isEmpty()) {
            throw new BusinessException("Excel sheet2 has no function split rows");
        }
        return rows;
    }

    private List<FunctionGroup> groupRows(List<FunctionSplitRow> rows) {
        Map<String, FunctionGroup> map = new LinkedHashMap<String, FunctionGroup>();
        for (FunctionSplitRow row : rows) {
            normalizeRow(row);
            String key = safe(row.getLevel1Module()) + "\u001f" + safe(row.getLevel2Module()) + "\u001f"
                    + safe(row.getLevel3Module()) + "\u001f" + safe(row.getFunctionProcess());
            FunctionGroup group = map.get(key);
            if (group == null) {
                group = new FunctionGroup(row.getLevel1Module(), row.getLevel2Module(), row.getLevel3Module(),
                        row.getFunctionProcess(), "");
                map.put(key, group);
            }
            group.rows.add(row);
            if (!StringUtils.hasText(group.description) && StringUtils.hasText(row.getFunctionDescription())) {
                group.description = row.getFunctionDescription();
            }
        }
        return new ArrayList<FunctionGroup>(map.values());
    }

    private void applyExportFunctionDescriptions(ProjectMetadata metadata, List<FunctionGroup> groups) {
        if (!isExcelImportProject(metadata)) {
            for (FunctionGroup group : groups) {
                if (!StringUtils.hasText(group.description)) {
                    group.description = buildGroupFunctionDescription(group.rows);
                }
            }
            return;
        }
        Map<String, List<FunctionSplitRow>> rowsByGroup = new LinkedHashMap<String, List<FunctionSplitRow>>();
        Map<String, FunctionGroup> groupByKey = new LinkedHashMap<String, FunctionGroup>();
        for (FunctionGroup group : groups) {
            String key = groupKey(group.level1, group.level2, group.level3, group.functionProcess);
            rowsByGroup.put(key, group.rows);
            groupByKey.put(key, group);
        }
        exportProgressService.startFunctionDescription(metadata.getId(), groups.size());
        Map<String, String> aiDescriptions;
        try {
            aiDescriptions = functionDescriptionAiService.generateDescriptions(rowsByGroup,
                    new FunctionDescriptionAiService.ProgressCallback() {
                        @Override
                        public void onStart(int total) {
                            exportProgressService.startFunctionDescription(metadata.getId(), total);
                        }

                        @Override
                        public void onBatchCompleted(int completedIncrement) {
                            exportProgressService.advanceFunctionDescription(metadata.getId(), completedIncrement);
                        }
                    });
        } finally {
            exportProgressService.finishFunctionDescription(metadata.getId());
        }
        for (Map.Entry<String, FunctionGroup> entry : groupByKey.entrySet()) {
            String description = aiDescriptions.get(entry.getKey());
            if (!StringUtils.hasText(description)) {
                description = buildGroupFunctionDescription(entry.getValue().rows);
            }
            entry.getValue().description = description;
        }
    }

    private boolean isExcelImportProject(ProjectMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        String fileType = safe(metadata.getFileType()).toLowerCase();
        return "xls".equals(fileType) || "xlsx".equals(fileType)
                || "EXCEL_UPLOADED".equalsIgnoreCase(metadata.getStatus())
                || "EXCEL_IMPORTED".equalsIgnoreCase(metadata.getStatus());
    }

    private void normalizeRow(FunctionSplitRow row) {
        if (!StringUtils.hasText(row.getLevel1Module())) {
            row.setLevel1Module(zh("\u672a\u5206\u7c7b\u6a21\u5757"));
        }
        if (!StringUtils.hasText(row.getLevel2Module())) {
            row.setLevel2Module(row.getLevel1Module());
        }
        if (!StringUtils.hasText(row.getLevel3Module())) {
            row.setLevel3Module(row.getLevel2Module());
        }
        if (!StringUtils.hasText(row.getFunctionProcess())) {
            row.setFunctionProcess(row.getLevel3Module());
        }
        if (!StringUtils.hasText(row.getSubProcessDescription())) {
            row.setSubProcessDescription(row.getFunctionProcess());
        }
        if (!StringUtils.hasText(row.getFunctionDescription())) {
            row.setFunctionDescription(row.getSubProcessDescription());
        }
        row.setSequenceDiagram(defaultSequenceDiagram(row.getMovementType(), row.getSubProcessDescription(),
                row.getDataGroup(), row.getDataAttributes()));
    }

    private void replaceFunctionRequirementSection(XWPFDocument document, List<FunctionGroup> groups) {
        int startIndex = findSectionIndex(document, zh("\u529f\u80fd\u9700\u6c42"), "4", 0);
        int endIndex = startIndex >= 0
                ? findSectionIndex(document, zh("\u975e\u529f\u80fd\u6027\u9700\u6c42\uff08\u53ef\u9009\uff09"), "4", startIndex + 1)
                : -1;

        XWPFParagraph last;
        if (startIndex >= 0) {
            if (endIndex > startIndex) {
                for (int i = endIndex - 1; i > startIndex; i--) {
                    document.removeBodyElement(i);
                }
            }
            last = (XWPFParagraph) document.getBodyElements().get(startIndex);
        } else {
            last = document.createParagraph();
            writeRun(last, zh("\u529f\u80fd\u9700\u6c42"), true);
            last.setStyle("4");
        }

        String lastLevel1 = null;
        String lastLevel2 = null;
        String lastLevel3 = null;
        for (FunctionGroup group : groups) {
            if (StringUtils.hasText(group.level1) && !group.level1.equals(lastLevel1)) {
                last = insertParagraphAfter(document, last, group.level1, "5", true);
                lastLevel1 = group.level1;
                lastLevel2 = null;
                lastLevel3 = null;
            }
            if (StringUtils.hasText(group.level2) && !group.level2.equals(lastLevel2)) {
                last = insertParagraphAfter(document, last, group.level2, "7", true);
                lastLevel2 = group.level2;
                lastLevel3 = null;
            }
            if (StringUtils.hasText(group.level3) && !group.level3.equals(lastLevel3)) {
                last = insertParagraphAfter(document, last, group.level3, "8", true);
                lastLevel3 = group.level3;
            }
            if (StringUtils.hasText(group.functionProcess)) {
                last = insertParagraphAfter(document, last, group.functionProcess + zh("\uff08\u65b0\u589e\uff09"), PROCESS_HEADING_STYLE, true);
            }
            last = insertParagraphAfter(document, last, zh("\u5173\u952e\u65f6\u5e8f\u56fe/\u4e1a\u52a1\u903b\u8f91\u56fe"), SUBSECTION_HEADING_STYLE, true);
            last = insertImageAfter(document, last, group);
            last = insertParagraphAfter(document, last, zh("\u672c\u65f6\u5e8f\u56fe\u6b65\u9aa4\u5982\u4e0b\uff1a"), SUBSECTION_HEADING_STYLE, true);
            int step = 1;
            for (FunctionSplitRow row : group.rows) {
                last = insertBodyParagraphAfter(document, last, step + ") " + safe(row.getSubProcessDescription())
                        + zh("\uff08") + safe(row.getDataAttributes()) + zh("\uff09"));
                step++;
            }
            last = insertParagraphAfter(document, last, zh("\u529f\u80fd\u63cf\u8ff0"), SUBSECTION_HEADING_STYLE, true);
            last = insertBodyParagraphAfter(document, last, safe(group.description));
        }
    }

    private String groupKey(String level1, String level2, String level3, String functionProcess) {
        return safe(level1) + "\u001f" + safe(level2) + "\u001f" + safe(level3) + "\u001f" + safe(functionProcess);
    }

    private String buildGroupFunctionDescription(List<FunctionSplitRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        FunctionSplitRow first = rows.get(0);
        String process = safe(first.getFunctionProcess());
        List<String> actions = new ArrayList<String>();
        Set<String> dataGroups = new LinkedHashSet<String>();
        Set<String> attributes = new LinkedHashSet<String>();
        for (FunctionSplitRow row : rows) {
            addUnique(actions, safe(row.getSubProcessDescription()));
            addUnique(dataGroups, safe(row.getDataGroup()));
            for (String attribute : splitAttributes(row.getDataAttributes())) {
                addUnique(attributes, attribute);
            }
        }
        StringBuilder builder = new StringBuilder();
        builder.append(zh("\u672c\u529f\u80fd\u7528\u4e8e\u5b8c\u6210")).append(process).append(zh("\uff0c"));
        builder.append(zh("\u7cfb\u7edf\u6309\u7167")).append(joinChinese(actions, zh("\u3001"))).append(zh("\u7684\u5904\u7406\u6d41\u7a0b"));
        if (!dataGroups.isEmpty()) {
            builder.append(zh("\uff0c\u56f4\u7ed5")).append(joinChinese(new ArrayList<String>(dataGroups), zh("\u3001"))).append(zh("\u7b49\u6570\u636e\u7ec4"));
        }
        if (!attributes.isEmpty()) {
            builder.append(zh("\uff0c\u5904\u7406")).append(joinChinese(limit(new ArrayList<String>(attributes), 12), zh("\u3001"))).append(zh("\u7b49\u4e1a\u52a1\u5c5e\u6027"));
        }
        builder.append(zh("\uff0c\u5f62\u6210\u8be5\u529f\u80fd\u7684\u8f93\u5165\u3001\u8bfb\u53d6\u3001\u5199\u5165\u6216\u8f93\u51fa\u7ed3\u679c\u3002"));
        return builder.toString();
    }

    private List<String> splitAttributes(String value) {
        List<String> result = new ArrayList<String>();
        String text = safe(value).replace('\uff0c', ',').replace('\u3001', ',').replace(';', ',').replace('\uff1b', ',');
        for (String part : text.split(",")) {
            addUnique(result, part.trim());
        }
        return result;
    }

    private List<String> limit(List<String> values, int max) {
        if (values.size() <= max) {
            return values;
        }
        return new ArrayList<String>(values.subList(0, max));
    }

    private void addUnique(List<String> values, String value) {
        if (StringUtils.hasText(value) && !values.contains(value)) {
            values.add(value);
        }
    }

    private void addUnique(Set<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(value);
        }
    }

    private String joinChinese(List<String> values, String delimiter) {
        if (values == null || values.isEmpty()) {
            return zh("\u4e1a\u52a1\u6570\u636e\u5904\u7406");
        }
        return String.join(delimiter, values);
    }

    private XWPFParagraph insertImageAfter(XWPFDocument document, XWPFParagraph anchor, FunctionGroup group) {
        try {
            byte[] image = buildUmlImage(group);
            BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(image));
            int width = bufferedImage == null ? 400 : bufferedImage.getWidth();
            int height = bufferedImage == null ? 220 : bufferedImage.getHeight();
            int targetWidth = width < 400 ? width : 400;
            int targetHeight = width < 400 ? height : Math.max(1, 400 * height / width);

            XWPFParagraph paragraph = insertBlankParagraphAfter(document, anchor);
            paragraph.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun run = paragraph.createRun();
            run.addPicture(new ByteArrayInputStream(image), XWPFDocument.PICTURE_TYPE_PNG, "sequence.png",
                    Units.toEMU(targetWidth), Units.toEMU(targetHeight));
            return paragraph;
        } catch (IOException ex) {
            return insertBodyParagraphAfter(document, anchor, zh("\u65f6\u5e8f\u56fe\u751f\u6210\u5931\u8d25\uff1a") + ex.getMessage());
        } catch (InvalidFormatException ex) {
            return insertBodyParagraphAfter(document, anchor, zh("\u65f6\u5e8f\u56fe\u63d2\u5165\u5931\u8d25\uff1a") + ex.getMessage());
        }
    }

    private byte[] buildUmlImage(FunctionGroup group) throws IOException {
        String uml = buildUml(group);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        SourceStringReader reader = new SourceStringReader(uml);
        reader.generateImage(outputStream, new FileFormatOption(FileFormat.PNG));
        return outputStream.toByteArray();
    }

    private String buildUml(FunctionGroup group) {
        List<UmlStep> steps = buildSteps(group);
        Set<String> participants = new LinkedHashSet<String>();
        for (UmlStep step : steps) {
            participants.add(step.from);
            participants.add(step.to);
        }

        StringBuilder builder = new StringBuilder();
        builder.append("@startuml\n");
        builder.append("title ").append(escapeUmlText(group.functionProcess)).append("\n\n");
        for (String participant : participants) {
            builder.append("participant \"").append(escapeUmlText(participant)).append("\" as ")
                    .append(alias(participant)).append("\n");
        }
        builder.append("\n");
        for (UmlStep step : steps) {
            builder.append(alias(step.from)).append(" -> ").append(alias(step.to)).append(" : ")
                    .append(escapeUmlText(step.label)).append("\n");
        }
        builder.append("\n@enduml\n");
        return builder.toString();
    }

    private List<UmlStep> buildSteps(FunctionGroup group) {
        List<UmlStep> steps = new ArrayList<UmlStep>();
        Set<String> seenFlows = new LinkedHashSet<String>();
        for (FunctionSplitRow row : group.rows) {
            String[] flows = splitFlows(row.getSequenceDiagram());
            String label = StringUtils.hasText(row.getSubProcessDescription()) ? row.getSubProcessDescription() : row.getFunctionProcess();
            for (String flow : flows) {
                String[] parts = flow.split("->", 2);
                if (parts.length == 2 && StringUtils.hasText(parts[0]) && StringUtils.hasText(parts[1])) {
                    String from = parts[0].trim();
                    String to = parts[1].trim();
                    String key = from + "->" + to;
                    if (seenFlows.add(key)) {
                        steps.add(new UmlStep(from, to, label));
                    }
                }
            }
        }
        if (steps.isEmpty()) {
            steps.add(new UmlStep("UI", "UI", group.functionProcess));
        }
        return steps;
    }

    private String[] splitFlows(String value) {
        String text = StringUtils.hasText(value) ? value : defaultSequenceDiagram("", "", "", "");
        return text.replace('\uff1b', ';').split(";");
    }

    private int findSectionIndex(XWPFDocument document, String text, String style, int fromIndex) {
        for (int i = fromIndex; i < document.getBodyElements().size(); i++) {
            if (!(document.getBodyElements().get(i) instanceof XWPFParagraph)) {
                continue;
            }
            XWPFParagraph paragraph = (XWPFParagraph) document.getBodyElements().get(i);
            if (text.equals(paragraph.getText().trim()) && (!StringUtils.hasText(style) || style.equals(paragraph.getStyle()))) {
                return i;
            }
        }
        return -1;
    }

    private XWPFParagraph insertParagraphAfter(XWPFDocument document, XWPFParagraph anchor, String text, String style, boolean bold) {
        XWPFParagraph paragraph = insertBlankParagraphAfter(document, anchor);
        if (StringUtils.hasText(style)) {
            paragraph.setStyle(style);
        }
        writeRun(paragraph, text, bold);
        return paragraph;
    }

    private XWPFParagraph insertBodyParagraphAfter(XWPFDocument document, XWPFParagraph anchor, String text) {
        return insertParagraphAfter(document, anchor, text, BODY_TEXT_STYLE, false);
    }

    private XWPFParagraph insertBlankParagraphAfter(XWPFDocument document, XWPFParagraph anchor) {
        XmlCursor cursor = anchor.getCTP().newCursor();
        cursor.toNextSibling();
        XWPFParagraph paragraph = document.insertNewParagraph(cursor);
        cursor.dispose();
        if (paragraph == null) {
            paragraph = document.createParagraph();
        }
        return paragraph;
    }

    private void writeRun(XWPFParagraph paragraph, String text, boolean bold) {
        XWPFRun run = paragraph.createRun();
        run.setText(text == null ? "" : text);
        run.setFontFamily("SimSun");
        run.setFontSize(11);
        run.setColor("000000");
        run.setBold(bold);
    }

    private String defaultSequenceDiagram(String movementType, String subProcessDescription, String dataGroup, String dataAttributes) {
        if ("E".equalsIgnoreCase(movementType)) {
            return "UI->UI";
        }
        if ("R".equalsIgnoreCase(movementType)) {
            if (isBackendRead(subProcessDescription, dataGroup, dataAttributes)) {
                return zh("\u540e\u53f0\u670d\u52a1->\u540e\u53f0\u670d\u52a1");
            }
            return zh("UI->\u540e\u53f0\u670d\u52a1");
        }
        if ("W".equalsIgnoreCase(movementType) || "X".equalsIgnoreCase(movementType)) {
            return zh("\u540e\u53f0\u670d\u52a1->UI");
        }
        return "UI->UI";
    }

    private boolean isBackendRead(String subProcessDescription, String dataGroup, String dataAttributes) {
        String text = safe(subProcessDescription) + " " + safe(dataGroup) + " " + safe(dataAttributes);
        return containsAny(text, zh("\u6570\u636e\u5904\u7406"), zh("\u8ba1\u7b97"), zh("\u6c47\u603b"), zh("\u7edf\u8ba1"),
                zh("\u6821\u9a8c"), zh("\u8f6c\u6362"), zh("\u4fdd\u5b58"), zh("\u5199\u5165"), zh("\u66f4\u65b0"));
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

    private String alias(String participant) {
        StringBuilder builder = new StringBuilder("P");
        String text = safe(participant);
        for (int i = 0; i < text.length(); i++) {
            builder.append(Integer.toHexString(text.charAt(i)));
        }
        return builder.toString();
    }

    private String escapeUmlText(String text) {
        return safe(text).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String buildWordOutputFileName(ProjectMetadata metadata) {
        return zh("\u9644\u4ef61-") + sourceBaseName(metadata) + ".docx";
    }

    private Path writableOutputPath(Path preferredPath) throws IOException {
        if (!Files.exists(preferredPath)) {
            return preferredPath;
        }
        return uniqueOutputPath(preferredPath);
    }

    private Path uniqueOutputPath(Path preferredPath) {
        String fileName = preferredPath.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String extension = dotIndex > 0 ? fileName.substring(dotIndex) : "";
        String suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return preferredPath.resolveSibling(baseName + "-" + suffix + extension);
    }

    private String sourceBaseName(ProjectMetadata metadata) {
        String name = metadata.getSourceFileName();
        if (!StringUtils.hasText(name)) {
            name = metadata.getName();
        }
        if (!StringUtils.hasText(name)) {
            name = metadata.getId();
        }
        name = Paths.get(name).getFileName().toString();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            name = name.substring(0, dotIndex);
        }
        return sanitizeFileName(name);
    }

    private String sanitizeFileName(String name) {
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return StringUtils.hasText(sanitized) ? sanitized : zh("\u672a\u547d\u540d\u9879\u76ee");
    }

    private List<CosmicResult> readCosmicResults(Path path) {
        if (!Files.exists(path)) {
            throw new BusinessException("COSMIC results file not found: " + path);
        }
        try {
            return objectMapper.readValue(path.toFile(), new TypeReference<List<CosmicResult>>() {
            });
        } catch (IOException ex) {
            throw new BusinessException("Read COSMIC results failed: " + path, ex);
        }
    }

    private double calculateTotalCfp(List<CosmicResult> results) {
        double total = 0;
        for (CosmicResult result : results) {
            total += result.getCfp();
        }
        return total;
    }

    private Path resolveTemplatePath() {
        if (StringUtils.hasText(configuredTemplatePath)) {
            Path path = Paths.get(configuredTemplatePath);
            if (Files.exists(path)) {
                return path;
            }
        }

        Path template = findTemplateInDirectory(Paths.get("templates"));
        if (template != null) {
            return template;
        }
        template = findTemplateInDirectory(Paths.get(".."));
        if (template != null) {
            return template;
        }
        return null;
    }

    private Path findTemplateInDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.docx")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.startsWith(zh("\u9644\u4ef61")) && !name.startsWith("~$")) {
                    return path;
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("Find Word template failed", ex);
        }
        return null;
    }

    private String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell, evaluator).trim();
    }

    private String carry(String current, String next) {
        return StringUtils.hasText(next) ? next : current;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String zh(String value) {
        return value;
    }

    private static class FunctionGroup {
        private final String level1;
        private final String level2;
        private final String level3;
        private final String functionProcess;
        private String description;
        private final List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();

        private FunctionGroup(String level1, String level2, String level3, String functionProcess, String description) {
            this.level1 = level1;
            this.level2 = level2;
            this.level3 = level3;
            this.functionProcess = functionProcess;
            this.description = description;
        }
    }

    private static class UmlStep {
        private final String from;
        private final String to;
        private final String label;

        private UmlStep(String from, String to, String label) {
            this.from = from;
            this.to = to;
            this.label = label;
        }
    }
}
