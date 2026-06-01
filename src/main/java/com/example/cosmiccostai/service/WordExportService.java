package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.WordExportResponse;
import com.example.cosmiccostai.model.CosmicResult;
import com.example.cosmiccostai.model.ProjectMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.xmlbeans.XmlCursor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class WordExportService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;
    private final Path outputBaseDir;
    private final String configuredTemplatePath;

    public WordExportService(FileStorageService fileStorageService,
                             @Value("${cosmic.output.base-dir:output}") String outputBaseDir,
                             @Value("${cosmic.template.word-path:}") String configuredTemplatePath) {
        this.fileStorageService = fileStorageService;
        this.objectMapper = new ObjectMapper();
        this.outputBaseDir = Paths.get(outputBaseDir);
        this.configuredTemplatePath = configuredTemplatePath;
    }

    public WordExportResponse export(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validateMetadata(metadata);

        List<CosmicResult> results = readCosmicResults(Paths.get(metadata.getCosmicResultsPath()));
        List<ChapterRow> chapterRows = readChapterRowsFromExcel(Paths.get(metadata.getExcelOutputPath()));
        double totalCfp = calculateTotalCfp(results);

        Path templatePath = resolveTemplatePath();
        Path projectOutputDir = outputBaseDir.resolve(projectId);
        Path outputPath = projectOutputDir.resolve(buildWordOutputFileName(metadata));
        Path actualOutputPath = outputPath;

        try {
            Files.createDirectories(projectOutputDir);
            actualOutputPath = writableOutputPath(outputPath);
            try (InputStream inputStream = Files.newInputStream(templatePath);
                 XWPFDocument document = new XWPFDocument(inputStream);
                 OutputStream outputStream = Files.newOutputStream(actualOutputPath)) {
                replaceFunctionRequirementSection(document, chapterRows);
                document.write(outputStream);
            }
        } catch (IOException ex) {
            throw new BusinessException("Export Word failed", ex);
        }

        metadata.setWordOutputPath(actualOutputPath.toString());
        metadata.setStatus("WORD_EXPORTED");
        metadata.setUpdatedAt(LocalDateTime.now().format(DATE_TIME_FORMATTER));
        fileStorageService.saveMetadata(metadata);

        WordExportResponse response = new WordExportResponse();
        response.setProjectId(projectId);
        response.setWordOutputPath(actualOutputPath.toString());
        response.setFunctionCount(results.size());
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

    private String buildWordOutputFileName(ProjectMetadata metadata) {
        return zh("\u9644\u4ef61-") + sourceBaseName(metadata) + ".docx";
    }

    private Path writableOutputPath(Path preferredPath) throws IOException {
        if (!Files.exists(preferredPath)) {
            return preferredPath;
        }
        try (OutputStream ignored = Files.newOutputStream(preferredPath)) {
            return preferredPath;
        } catch (IOException ex) {
            return uniqueOutputPath(preferredPath);
        }
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
        return StringUtils.hasText(sanitized) ? sanitized : "未命名项目";
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

    private List<ChapterRow> readChapterRowsFromExcel(Path excelPath) {
        List<ChapterRow> rows = new ArrayList<ChapterRow>();
        DataFormatter formatter = new DataFormatter();
        try (InputStream inputStream = Files.newInputStream(excelPath);
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Sheet sheet = workbook.getSheet(zh("\u0032\u3001\u529f\u80fd\u70b9\u62c6\u5206\u8868"));
            if (sheet == null) {
                sheet = workbook.getSheetAt(2);
            }

            String currentLevel1 = "";
            String currentLevel2 = "";
            String currentLevel3 = "";
            String currentProcess = "";
            for (int rowIndex = 5; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }

                String level1 = cellText(row.getCell(1), formatter, evaluator);
                String level2 = cellText(row.getCell(2), formatter, evaluator);
                String level3 = cellText(row.getCell(3), formatter, evaluator);
                String process = cellText(row.getCell(6), formatter, evaluator);
                String subProcess = cellText(row.getCell(7), formatter, evaluator);
                String movementType = cellText(row.getCell(8), formatter, evaluator);
                String reuseDegree = cellText(row.getCell(11), formatter, evaluator);

                if (StringUtils.hasText(level1)) {
                    currentLevel1 = level1;
                }
                if (StringUtils.hasText(level2)) {
                    currentLevel2 = level2;
                } else if (StringUtils.hasText(currentLevel1)) {
                    currentLevel2 = currentLevel1;
                }
                if (StringUtils.hasText(level3)) {
                    currentLevel3 = level3;
                } else if (StringUtils.hasText(currentLevel2)) {
                    currentLevel3 = currentLevel2;
                }
                if (StringUtils.hasText(process)) {
                    currentProcess = process;
                } else if (StringUtils.hasText(currentLevel3)) {
                    currentProcess = currentLevel3;
                }

                if (!StringUtils.hasText(subProcess) && !StringUtils.hasText(movementType)) {
                    continue;
                }
                if (!StringUtils.hasText(currentProcess)) {
                    continue;
                }
                rows.add(new ChapterRow(currentLevel1, currentLevel2, currentLevel3, currentProcess, subProcess, movementType, reuseDegree));
            }
        } catch (IOException ex) {
            throw new BusinessException("Read Excel for Word export failed: " + excelPath, ex);
        }
        if (rows.isEmpty()) {
            throw new BusinessException("Excel sheet2 has no function split rows");
        }
        return rows;
    }

    private void replaceFunctionRequirementSection(XWPFDocument document, List<ChapterRow> rows) {
        int startIndex = findSectionIndex(document, zh("\u529f\u80fd\u9700\u6c42"), "4", 0);
        if (startIndex < 0) {
            throw new BusinessException("Word template section not found: " + zh("\u529f\u80fd\u9700\u6c42"));
        }
        int endIndex = findSectionIndex(document, zh("\u975e\u529f\u80fd\u6027\u9700\u6c42\uff08\u53ef\u9009\uff09"), "4", startIndex + 1);
        if (endIndex < 0) {
            throw new BusinessException("Word template next section not found: " + zh("\u975e\u529f\u80fd\u6027\u9700\u6c42\uff08\u53ef\u9009\uff09"));
        }

        for (int i = endIndex - 1; i > startIndex; i--) {
            document.removeBodyElement(i);
        }

        XWPFParagraph anchor = (XWPFParagraph) document.getBodyElements().get(startIndex);
        XWPFParagraph last = anchor;
        String lastLevel1 = null;
        String lastLevel2 = null;
        String lastLevel3 = null;
        String lastProcess = null;

        for (ChapterRow row : rows) {
            if (StringUtils.hasText(row.level1) && !row.level1.equals(lastLevel1)) {
                last = insertParagraphAfter(document, last, row.level1, "5", true);
                lastLevel1 = row.level1;
                lastLevel2 = null;
                lastLevel3 = null;
                lastProcess = null;
            }
            if (StringUtils.hasText(row.level2) && !row.level2.equals(lastLevel2)) {
                last = insertParagraphAfter(document, last, row.level2, "7", true);
                lastLevel2 = row.level2;
                lastLevel3 = null;
                lastProcess = null;
            }
            if (StringUtils.hasText(row.level3) && !row.level3.equals(lastLevel3)) {
                last = insertParagraphAfter(document, last, row.level3, "8", true);
                lastLevel3 = row.level3;
                lastProcess = null;
            }
                if (!row.process.equals(lastProcess)) {
                    last = insertParagraphAfter(document, last, row.process + zh("\uff08\u65b0\u589e\uff09"), "9", true);
                    lastProcess = row.process;
                }
            last = insertParagraphAfter(document, last, buildSubProcessText(row), "10", false);
        }
    }

    private int findSectionIndex(XWPFDocument document, String text, String style, int fromIndex) {
        for (int i = fromIndex; i < document.getBodyElements().size(); i++) {
            if (!(document.getBodyElements().get(i) instanceof XWPFParagraph)) {
                continue;
            }
            XWPFParagraph paragraph = (XWPFParagraph) document.getBodyElements().get(i);
            if (text.equals(paragraph.getText().trim()) && style.equals(paragraph.getStyle())) {
                return i;
            }
        }
        return -1;
    }

    private XWPFParagraph insertParagraphAfter(XWPFDocument document, XWPFParagraph anchor, String text, String style, boolean bold) {
        XmlCursor cursor = anchor.getCTP().newCursor();
        cursor.toNextSibling();
        XWPFParagraph paragraph = document.insertNewParagraph(cursor);
        cursor.dispose();
        if (StringUtils.hasText(style)) {
            paragraph.setStyle(style);
        }
        XWPFRun run = paragraph.createRun();
        run.setText(text == null ? "" : text);
        run.setFontFamily("SimSun");
        run.setFontSize(11);
        run.setColor("000000");
        run.setBold(bold);
        return paragraph;
    }

    private String buildSubProcessText(ChapterRow row) {
        String type = StringUtils.hasText(row.movementType) ? row.movementType + " " : "";
        String reuse = safe(row.reuseDegree);
        if (zh("\u590d\u7528").equals(reuse) || zh("\u5229\u65e7").equals(reuse)) {
            return type + safe(row.subProcess) + zh("\uff08") + reuse + zh("\uff09");
        }
        return type + safe(row.subProcess);
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

        Path parent = Paths.get("..");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, "*.docx")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.startsWith(zh("\u9644\u4ef61")) && !name.startsWith("~$")) {
                    return path;
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("Find Word template failed", ex);
        }
        throw new BusinessException("Word template not found");
    }

    private String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell, evaluator).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String zh(String value) {
        return value;
    }

    private static class ChapterRow {

        private final String level1;
        private final String level2;
        private final String level3;
        private final String process;
        private final String subProcess;
        private final String movementType;
        private final String reuseDegree;

        private ChapterRow(String level1, String level2, String level3, String process, String subProcess, String movementType, String reuseDegree) {
            this.level1 = level1;
            this.level2 = level2;
            this.level3 = level3;
            this.process = process;
            this.subProcess = subProcess;
            this.movementType = movementType;
            this.reuseDegree = reuseDegree;
        }
    }
}
