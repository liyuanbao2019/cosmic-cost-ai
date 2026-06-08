package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.ReviewResponse;
import com.example.cosmiccostai.model.FunctionSplitRow;
import com.example.cosmiccostai.model.ProjectMetadata;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ExcelReviewImportService {

    private static final int FALLBACK_DATA_START_ROW_INDEX = 5;
    private static final int MAX_ERROR_COUNT = 10;

    private final FileStorageService fileStorageService;
    private final ReviewService reviewService;
    private final DataFormatter dataFormatter = new DataFormatter();

    public ExcelReviewImportService(FileStorageService fileStorageService, ReviewService reviewService) {
        this.fileStorageService = fileStorageService;
        this.reviewService = reviewService;
    }

    public ReviewResponse importSplitExcel(MultipartFile file, String projectName) {
        ProjectMetadata metadata = fileStorageService.uploadSplitExcel(file, projectName);
        List<FunctionSplitRow> rows = readRows(metadata);
        return reviewService.importReview(metadata.getId(), rows);
    }

    private List<FunctionSplitRow> readRows(ProjectMetadata metadata) {
        try (InputStream inputStream = Files.newInputStream(Paths.get(metadata.getStoredFilePath()));
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = resolveSplitSheet(workbook);
            List<String> errors = new ArrayList<String>();
            List<FunctionSplitRow> rows = parseRows(sheet, metadata, errors);
            if (rows.isEmpty()) {
                errors.add("未读取到有效拆分数据：请确认功能点拆分数据从第 6 行开始，并填写 A-M 列");
            }
            if (!errors.isEmpty()) {
                throw new BusinessException("导入已拆分 Excel 失败：" + joinErrors(errors));
            }
            return rows;
        } catch (IOException ex) {
            throw new BusinessException("读取已拆分 Excel 失败，请确认文件未损坏且格式为 xlsx/xls", ex);
        }
    }

    private Sheet resolveSplitSheet(Workbook workbook) {
        Sheet sheet = workbook.getSheet(zh("\u0032\u3001\u529f\u80fd\u70b9\u62c6\u5206\u8868"));
        if (sheet != null) {
            return sheet;
        }
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet candidate = workbook.getSheetAt(i);
            if (candidate != null && candidate.getSheetName() != null
                    && candidate.getSheetName().contains(zh("\u529f\u80fd\u70b9\u62c6\u5206"))) {
                return candidate;
            }
        }
        Sheet detected = detectSplitSheetByHeaders(workbook);
        if (detected != null) {
            return detected;
        }
        throw new BusinessException("导入失败：Excel 中未找到功能点拆分表页。请使用包含“"
                + zh("\u0032\u3001\u529f\u80fd\u70b9\u62c6\u5206\u8868") + "”工作表的附件3模板，当前工作表："
                + availableSheetNames(workbook));
    }

    private Sheet detectSplitSheetByHeaders(Workbook workbook) {
        Sheet bestSheet = null;
        int bestScore = 0;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            int score = scoreSheetHeaders(sheet);
            if (score > bestScore) {
                bestScore = score;
                bestSheet = sheet;
            }
        }
        return bestScore >= 3 ? bestSheet : null;
    }

    private int scoreSheetHeaders(Sheet sheet) {
        int score = 0;
        int lastRow = Math.min(sheet.getLastRowNum(), 12);
        for (int rowIndex = 0; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            String text = "";
            for (int colIndex = 0; colIndex <= 14; colIndex++) {
                text += " " + cellText(row, colIndex);
            }
            if (text.contains(zh("\u5ba2\u6237\u9700\u6c42"))) {
                score++;
            }
            if (text.contains(zh("\u4e00\u7ea7\u6a21\u5757"))) {
                score++;
            }
            if (text.contains(zh("\u529f\u80fd\u8fc7\u7a0b"))) {
                score++;
            }
            if (text.contains(zh("\u6570\u636e\u79fb\u52a8"))) {
                score++;
            }
            if (text.contains(zh("\u590d\u7528\u5ea6"))) {
                score++;
            }
        }
        return score;
    }

    private String availableSheetNames(Workbook workbook) {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            names.add(workbook.getSheetName(i));
        }
        return String.join("、", names);
    }

    private List<FunctionSplitRow> parseRows(Sheet sheet, ProjectMetadata metadata, List<String> errors) {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        FunctionSplitRow previous = null;
        int dataStartRowIndex = resolveDataStartRowIndex(sheet);
        for (int rowIndex = dataStartRowIndex; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row excelRow = sheet.getRow(rowIndex);
            if (excelRow == null) {
                continue;
            }
            String[] values = readValues(excelRow);
            if (isBlankRow(values)) {
                continue;
            }

            FunctionSplitRow row = new FunctionSplitRow();
            row.setProjectName(firstText(values[0], previous == null ? null : previous.getProjectName(), metadata.getName()));
            row.setLevel1Module(firstText(values[1], previous == null ? null : previous.getLevel1Module()));
            row.setLevel2Module(firstText(values[2], previous == null ? null : previous.getLevel2Module(), row.getLevel1Module()));
            row.setLevel3Module(firstText(values[3], previous == null ? null : previous.getLevel3Module(), row.getLevel2Module()));
            row.setFunctionalUser(firstText(values[4], previous == null ? null : previous.getFunctionalUser()));
            row.setTriggerEvent(firstText(values[5], previous == null ? null : previous.getTriggerEvent()));
            row.setFunctionProcess(firstText(values[6], previous == null ? null : previous.getFunctionProcess()));
            row.setSubProcessDescription(clean(values[7]));
            row.setMovementType(normalizeMovementType(values[8]));
            row.setDataGroup(clean(values[9]));
            row.setDataAttributes(clean(values[10]));
            String rawReuseDegree = clean(values[11]);
            row.setReuseDegree(StringUtils.hasText(rawReuseDegree) ? normalizeReuseDegree(rawReuseDegree) : zh("\u65b0\u589e"));
            row.setCfp(cfpByReuse(row.getReuseDegree()));
            row.setFunctionDescription(clean(values[13]));
            row.setSequenceDiagram(clean(values[14]));

            validateRow(row, rowIndex + 1, values[8], rawReuseDegree, errors);
            rows.add(row);
            previous = row;
        }
        return rows;
    }

    private int resolveDataStartRowIndex(Sheet sheet) {
        int headerRowIndex = -1;
        int lastHeaderCandidate = Math.min(sheet.getLastRowNum(), 15);
        for (int rowIndex = 0; rowIndex <= lastHeaderCandidate; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            String rowText = "";
            for (int columnIndex = 0; columnIndex <= 14; columnIndex++) {
                rowText += " " + cellText(row, columnIndex);
            }
            if (rowText.contains(zh("\u529f\u80fd\u8fc7\u7a0b"))
                    && rowText.contains(zh("\u6570\u636e\u79fb\u52a8"))
                    && rowText.contains(zh("\u6570\u636e\u7ec4"))) {
                headerRowIndex = rowIndex;
                break;
            }
        }
        if (headerRowIndex < 0) {
            return FALLBACK_DATA_START_ROW_INDEX;
        }
        for (int rowIndex = headerRowIndex + 1; rowIndex <= Math.min(sheet.getLastRowNum(), headerRowIndex + 8); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            String movementType = normalizeMovementType(cellText(row, 8));
            if (StringUtils.hasText(movementType)
                    && StringUtils.hasText(cellText(row, 7))
                    && StringUtils.hasText(cellText(row, 9))
                    && StringUtils.hasText(cellText(row, 10))) {
                return rowIndex;
            }
        }
        return Math.min(FALLBACK_DATA_START_ROW_INDEX, sheet.getLastRowNum());
    }

    private String[] readValues(Row row) {
        String[] values = new String[15];
        for (int i = 0; i < values.length; i++) {
            values[i] = cellText(row, i);
        }
        return values;
    }

    private String cellText(Row row, int columnIndex) {
        Cell cell = row.getCell(columnIndex);
        return cell == null ? "" : clean(dataFormatter.formatCellValue(cell));
    }

    private boolean isBlankRow(String[] values) {
        for (int i = 0; i <= 14; i++) {
            if (StringUtils.hasText(values[i])) {
                return false;
            }
        }
        return true;
    }

    private void validateRow(FunctionSplitRow row, int rowNumber, String rawMovementType, String rawReuseDegree, List<String> errors) {
        requireText(errors, rowNumber, row.getLevel1Module(), "B列一级模块");
        requireText(errors, rowNumber, row.getFunctionProcess(), "G列功能过程");
        requireText(errors, rowNumber, row.getSubProcessDescription(), "H列子过程描述");
        requireText(errors, rowNumber, row.getDataGroup(), "J列数据组");
        requireText(errors, rowNumber, row.getDataAttributes(), "K列数据属性");
        if (StringUtils.hasText(rawMovementType) && !StringUtils.hasText(row.getMovementType())) {
            errors.add("第 " + rowNumber + " 行：I列数据移动类型无效，应为 E/R/W/X，当前为 " + rawMovementType);
        } else if (!StringUtils.hasText(row.getMovementType())) {
            errors.add("第 " + rowNumber + " 行：I列数据移动类型必填，应为 E/R/W/X");
        }
        if (StringUtils.hasText(rawReuseDegree) && !StringUtils.hasText(row.getReuseDegree())) {
            errors.add("第 " + rowNumber + " 行：L列复用度无效，应为新增/复用/利旧，当前为 " + rawReuseDegree);
        }
    }

    private void requireText(List<String> errors, int rowNumber, String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            errors.add("第 " + rowNumber + " 行：" + fieldName + "必填");
        }
    }

    private String normalizeMovementType(String value) {
        String normalized = clean(value).toUpperCase(Locale.ROOT);
        if ("E".equals(normalized) || "ENTRY".equals(normalized) || zh("\u8f93\u5165").equals(normalized)
                || zh("\u63a5\u6536").equals(normalized)) {
            return "E";
        }
        if ("R".equals(normalized) || "READ".equals(normalized) || zh("\u8bfb\u53d6").equals(normalized)
                || zh("\u67e5\u8be2").equals(normalized)) {
            return "R";
        }
        if ("W".equals(normalized) || "WRITE".equals(normalized) || zh("\u5199\u5165").equals(normalized)
                || zh("\u4fdd\u5b58").equals(normalized) || zh("\u66f4\u65b0").equals(normalized)) {
            return "W";
        }
        if ("X".equals(normalized) || "EXIT".equals(normalized) || zh("\u8f93\u51fa").equals(normalized)
                || zh("\u8fd4\u56de").equals(normalized)) {
            return "X";
        }
        return "";
    }

    private String normalizeReuseDegree(String value) {
        String normalized = clean(value);
        if (zh("\u65b0\u589e").equals(normalized) || zh("\u65b0\u5efa").equals(normalized)) {
            return zh("\u65b0\u589e");
        }
        if (zh("\u590d\u7528").equals(normalized)) {
            return zh("\u590d\u7528");
        }
        if (zh("\u5229\u65e7").equals(normalized)) {
            return zh("\u5229\u65e7");
        }
        return "";
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

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return clean(value);
            }
        }
        return "";
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00a0', ' ').trim();
    }

    private String joinErrors(List<String> errors) {
        List<String> displayed = errors;
        if (errors.size() > MAX_ERROR_COUNT) {
            displayed = errors.subList(0, MAX_ERROR_COUNT);
        }
        String message = String.join("; ", displayed);
        if (errors.size() > MAX_ERROR_COUNT) {
            message = message + "; 还有 " + (errors.size() - MAX_ERROR_COUNT) + " 个问题";
        }
        return message;
    }

    private String zh(String value) {
        return value;
    }
}
