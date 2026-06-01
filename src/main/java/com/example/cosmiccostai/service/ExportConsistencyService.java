package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.ConsistencyCheckResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExportConsistencyService {

    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public ExportConsistencyService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
        this.objectMapper = new ObjectMapper();
    }

    public ConsistencyCheckResponse check(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        validate(metadata);

        List<CosmicResult> sourceResults = readSource(Paths.get(metadata.getCosmicResultsPath()));
        Map<String, CosmicResult> sourceMap = toMap(sourceResults);
        Map<String, CosmicResult> excelMap = readExcel(Paths.get(metadata.getExcelOutputPath()));
        Map<String, CosmicResult> wordMap = readWord(Paths.get(metadata.getWordOutputPath()));

        ConsistencyCheckResponse response = new ConsistencyCheckResponse();
        response.setProjectId(projectId);
        response.setSourceFunctionCount(sourceMap.size());
        response.setExcelFunctionCount(excelMap.size());
        response.setWordFunctionCount(wordMap.size());
        response.setSourceTotalCfp(total(sourceMap));
        response.setExcelTotalCfp(total(excelMap));
        response.setWordTotalCfp(total(wordMap));

        compare("Excel", sourceMap, excelMap, response);
        compare("Word", sourceMap, wordMap, response);
        response.setMatched(response.getMessages().isEmpty());
        if (response.isMatched()) {
            response.addMessage("Excel and Word are consistent with reviewed COSMIC results.");
        }
        return response;
    }

    private void validate(ProjectMetadata metadata) {
        if (metadata == null || !StringUtils.hasText(metadata.getCosmicResultsPath())) {
            throw new BusinessException("Project has not completed COSMIC analysis yet");
        }
        if (!StringUtils.hasText(metadata.getExcelOutputPath())) {
            throw new BusinessException("Excel has not been exported yet");
        }
        if (!StringUtils.hasText(metadata.getWordOutputPath())) {
            throw new BusinessException("Word has not been exported yet");
        }
    }

    private List<CosmicResult> readSource(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), new TypeReference<List<CosmicResult>>() {
            });
        } catch (IOException ex) {
            throw new BusinessException("Read source COSMIC results failed", ex);
        }
    }

    private Map<String, CosmicResult> readExcel(Path path) {
        if (!Files.exists(path)) {
            throw new BusinessException("Excel file not found: " + path);
        }
        Map<String, CosmicResult> map = new LinkedHashMap<String, CosmicResult>();
        DataFormatter formatter = new DataFormatter();
        try (InputStream inputStream = Files.newInputStream(path);
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Sheet sheet = workbook.getSheet("2、功能点拆分表");
            if (sheet == null) {
                sheet = workbook.getSheetAt(2);
            }
            for (int rowIndex = 5; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                String function = cellText(row.getCell(6), formatter, evaluator);
                if (!StringUtils.hasText(function)) {
                    continue;
                }
                CosmicResult result = map.get(function);
                if (result == null) {
                    result = new CosmicResult();
                    result.setModule(cellText(row.getCell(1), formatter, evaluator));
                    result.setFunction(function);
                    map.put(function, result);
                }
                accumulateMovement(result, cellText(row.getCell(8), formatter, evaluator), cfpByReuse(cellText(row.getCell(11), formatter, evaluator)));
                map.put(function, result);
            }
        } catch (IOException ex) {
            throw new BusinessException("Read Excel file failed", ex);
        }
        return map;
    }

    private Map<String, CosmicResult> readWord(Path path) {
        if (!Files.exists(path)) {
            throw new BusinessException("Word file not found: " + path);
        }
        Map<String, CosmicResult> map = new LinkedHashMap<String, CosmicResult>();
        try (InputStream inputStream = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(inputStream)) {
            String currentModule = "";
            CosmicResult current = null;
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText() == null ? "" : paragraph.getText().trim();
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                if ("4".equals(paragraph.getStyle()) && text.startsWith(zh("\u975e\u529f\u80fd\u6027\u9700\u6c42"))) {
                    break;
                }
                if ("5".equals(paragraph.getStyle())) {
                    currentModule = text;
                    continue;
                }
                if ("9".equals(paragraph.getStyle())) {
                    String function = stripChangeLabel(text);
                    current = map.get(function);
                    if (current == null) {
                        current = new CosmicResult();
                        current.setModule(currentModule);
                        current.setFunction(function);
                        map.put(function, current);
                    }
                    map.put(current.getFunction(), current);
                    continue;
                }
                if (current != null && "10".equals(paragraph.getStyle())) {
                    accumulateWordMovement(current, text);
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("Read Word file failed", ex);
        }
        return map;
    }

    private void accumulateWordMovement(CosmicResult result, String text) {
        String type = text.length() > 0 ? text.substring(0, 1) : "";
        accumulateMovement(result, type, cfpByReuse(extractReuseDegree(text)));
    }

    private void accumulateMovement(CosmicResult result, String type, double cfp) {
        if ("E".equals(type)) {
            result.setEntry(result.getEntry() + 1);
        } else if ("R".equals(type)) {
            result.setRead(result.getRead() + 1);
        } else if ("W".equals(type)) {
            result.setWrite(result.getWrite() + 1);
        } else if ("X".equals(type)) {
            result.setExit(result.getExit() + 1);
        } else {
            return;
        }
        result.setCfp(result.getCfp() + cfp);
    }

    private String extractReuseDegree(String text) {
        if (text != null && text.contains(zh("\uff08\u590d\u7528\uff09"))) {
            return zh("\u590d\u7528");
        }
        if (text != null && text.contains(zh("\uff08\u5229\u65e7\uff09"))) {
            return zh("\u5229\u65e7");
        }
        return zh("\u65b0\u589e");
    }

    private String stripChangeLabel(String text) {
        return text.replace(zh("\uff08\u65b0\u589e\uff09"), "")
                .replace(zh("\uff08\u4f18\u5316\uff09"), "")
                .replace(zh("\uff08\u5220\u9664\uff09"), "")
                .trim();
    }

    private Map<String, CosmicResult> toMap(List<CosmicResult> results) {
        Map<String, CosmicResult> map = new LinkedHashMap<String, CosmicResult>();
        for (CosmicResult result : results) {
            map.put(result.getFunction(), result);
        }
        return map;
    }

    private void compare(String targetName, Map<String, CosmicResult> source, Map<String, CosmicResult> target, ConsistencyCheckResponse response) {
        if (source.size() != target.size()) {
            response.addMessage(targetName + " function count mismatch: source=" + source.size() + ", target=" + target.size());
        }
        for (Map.Entry<String, CosmicResult> entry : source.entrySet()) {
            CosmicResult targetResult = target.get(entry.getKey());
            if (targetResult == null) {
                response.addMessage(targetName + " missing function: " + entry.getKey());
                continue;
            }
            compareNumbers(targetName, entry.getKey(), entry.getValue(), targetResult, response);
        }
    }

    private void compareNumbers(String targetName, String function, CosmicResult source, CosmicResult target, ConsistencyCheckResponse response) {
        if (source.getEntry() != target.getEntry()
                || source.getRead() != target.getRead()
                || source.getWrite() != target.getWrite()
                || source.getExit() != target.getExit()
                || Math.abs(source.getCfp() - target.getCfp()) > 0.0001) {
            response.addMessage(targetName + " mismatch for " + function);
        }
    }

    private double total(Map<String, CosmicResult> map) {
        double total = 0;
        for (CosmicResult result : map.values()) {
            total += result.getCfp();
        }
        return total;
    }

    private double cfpByReuse(String reuseDegree) {
        if (zh("\u590d\u7528").equals(reuseDegree)) {
            return 1.0 / 3.0;
        }
        if (zh("\u5229\u65e7").equals(reuseDegree)) {
            return 0;
        }
        return 1;
    }

    private String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell, evaluator).trim();
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "0" : value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String zh(String value) {
        return value;
    }
}
