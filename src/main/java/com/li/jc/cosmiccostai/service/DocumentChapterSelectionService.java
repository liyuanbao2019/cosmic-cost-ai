package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.DocumentChapterItem;
import com.li.jc.cosmiccostai.dto.DocumentChapterSelectionResponse;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits the locally parsed full document into selectable top-level chapters. */
@Service
public class DocumentChapterSelectionService {

    private static final Pattern ARABIC_HEADING = Pattern.compile("^(\\d+)[.．、](?!\\d)\\s*(.{1,60})$");
    private static final Pattern CHINESE_HEADING = Pattern.compile("^([一二三四五六七八九十百]+)[、.．]\\s*(.{1,60})$");

    private final FileStorageService fileStorageService;
    private final RequirementInputService requirementInputService;

    public DocumentChapterSelectionService(FileStorageService fileStorageService,
                                           RequirementInputService requirementInputService) {
        this.fileStorageService = fileStorageService;
        this.requirementInputService = requirementInputService;
    }

    public DocumentChapterSelectionResponse get(String projectId) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        String documentText = read(metadata.getDocumentTextPath(), "项目尚未完成全文解析");
        String currentInput = readOptional(metadata.getRequirementDetailPath());
        return buildResponse(parseChapters(projectId, documentText), documentText, currentInput, null);
    }

    public DocumentChapterSelectionResponse apply(String projectId, List<String> selectedIds) {
        ProjectMetadata metadata = fileStorageService.loadMetadata(projectId);
        String documentText = read(metadata.getDocumentTextPath(), "项目尚未完成全文解析");
        List<ChapterBlock> chapters = parseChapters(projectId, documentText);
        Set<String> selected = new HashSet<String>(selectedIds == null
                ? Collections.<String>emptyList() : selectedIds);
        if (selected.isEmpty()) {
            throw new BusinessException("请至少选择一个要送给 AI 的章节");
        }
        StringBuilder aiInput = new StringBuilder();
        for (ChapterBlock chapter : chapters) {
            if (selected.contains(chapter.id)) {
                if (aiInput.length() > 0) {
                    aiInput.append(System.lineSeparator()).append(System.lineSeparator());
                }
                aiInput.append(chapter.text.trim());
            }
        }
        if (!StringUtils.hasText(aiInput.toString())) {
            throw new BusinessException("所选章节没有可用正文，请重新选择");
        }
        requirementInputService.saveManualInput(projectId, aiInput.toString());
        return buildResponse(chapters, documentText, aiInput.toString(), selected);
    }

    private DocumentChapterSelectionResponse buildResponse(List<ChapterBlock> chapters,
                                                            String documentText,
                                                            String currentInput,
                                                            Set<String> explicitSelection) {
        DocumentChapterSelectionResponse response = new DocumentChapterSelectionResponse();
        response.setDocumentLength(documentText.length());
        response.setAiInput(currentInput == null ? "" : currentInput);
        Set<String> selectedIds = explicitSelection == null
                ? inferredSelection(chapters, documentText, currentInput)
                : explicitSelection;
        String recommendedId = recommendedChapterId(chapters);
        int selectedLength = 0;
        List<DocumentChapterItem> items = new ArrayList<DocumentChapterItem>();
        for (ChapterBlock chapter : chapters) {
            boolean recommended = chapter.id.equals(recommendedId);
            boolean selected = selectedIds.contains(chapter.id);
            DocumentChapterItem item = new DocumentChapterItem();
            item.setId(chapter.id);
            item.setNumber(chapter.number);
            item.setTitle(chapter.title);
            item.setCharCount(chapter.text.length());
            item.setSelected(selected);
            item.setRecommended(recommended);
            items.add(item);
            if (selected) {
                selectedLength += chapter.text.length();
            }
        }
        response.setChapters(items);
        response.setSelectedLength(selectedLength);
        return response;
    }

    private Set<String> inferredSelection(List<ChapterBlock> chapters,
                                          String documentText,
                                          String currentInput) {
        Set<String> selected = new HashSet<String>();
        String normalizedInput = normalizeForComparison(currentInput);
        String normalizedDocument = normalizeForComparison(documentText);
        boolean wholeDocumentInput = StringUtils.hasText(normalizedInput)
                && normalizedInput.length() >= normalizedDocument.length() * 0.85D;
        if (!wholeDocumentInput) {
            for (ChapterBlock chapter : chapters) {
                if (isPresentInInput(currentInput, chapter)) {
                    selected.add(chapter.id);
                }
            }
        }
        if (selected.isEmpty()) {
            String recommendedId = recommendedChapterId(chapters);
            if (StringUtils.hasText(recommendedId)) {
                selected.add(recommendedId);
            }
        }
        return selected;
    }

    private List<ChapterBlock> parseChapters(String projectId, String documentText) {
        List<ChapterBlock> outlineChapters = parseOutlineChapters(projectId, documentText);
        if (!outlineChapters.isEmpty()) {
            return outlineChapters;
        }
        return parseTextChapters(documentText);
    }

    private List<ChapterBlock> parseOutlineChapters(String projectId, String documentText) {
        Path outlinePath = fileStorageService.resolveProjectDir(projectId).resolve("document-outline.tsv");
        if (!Files.exists(outlinePath)) {
            return Collections.emptyList();
        }
        List<String> outlineLines;
        try {
            outlineLines = Files.readAllLines(outlinePath, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return Collections.emptyList();
        }
        List<OutlineHeading> outlineHeadings = new ArrayList<OutlineHeading>();
        for (String outlineLine : outlineLines) {
            String[] parts = outlineLine.split("\\t", 2);
            if (parts.length != 2 || !parts[0].matches("[1-9]") || !StringUtils.hasText(parts[1])) {
                continue;
            }
            outlineHeadings.add(new OutlineHeading(Integer.parseInt(parts[0]),
                    parseOutlineHeading(parts[1].trim())));
        }
        if (outlineHeadings.isEmpty()) {
            return Collections.emptyList();
        }
        String[] lines = documentText.split("\\r?\\n");
        List<OutlineHeading> located = new ArrayList<OutlineHeading>();
        int searchFrom = 0;
        for (OutlineHeading outlineHeading : outlineHeadings) {
            int found = findExactLine(lines, outlineHeading.heading.originalTitle, searchFrom);
            if (found >= 0) {
                outlineHeading.lineIndex = found;
                located.add(outlineHeading);
                searchFrom = found + 1;
            }
        }
        if (located.isEmpty()) {
            return Collections.emptyList();
        }
        int shallowestLevel = shallowestLevel(located);
        int selectionLevel = selectionLevel(located, shallowestLevel);
        List<OutlineHeading> selectedHeadings = headingsAtLevel(located, selectionLevel);
        if (selectedHeadings.isEmpty()) {
            return Collections.emptyList();
        }
        List<ChapterBlock> chapters = new ArrayList<ChapterBlock>();
        boolean skippedSingleDocumentTitle = selectionLevel > shallowestLevel
                && countAtLevel(located, shallowestLevel) == 1;
        if (!skippedSingleDocumentTitle && selectedHeadings.get(0).lineIndex > 0) {
            String preface = join(lines, 0, selectedHeadings.get(0).lineIndex);
            if (StringUtils.hasText(preface)) {
                chapters.add(new ChapterBlock("PREFACE", "", "章节前置内容", preface));
            }
        }
        for (int i = 0; i < selectedHeadings.size(); i++) {
            OutlineHeading selected = selectedHeadings.get(i);
            int end = outlineSectionEnd(located, selected, lines.length);
            chapters.add(new ChapterBlock("OUTLINE" + (i + 1), selected.heading.number,
                    selected.heading.title, join(lines, selected.lineIndex, end)));
        }
        return chapters;
    }

    private int shallowestLevel(List<OutlineHeading> headings) {
        int level = Integer.MAX_VALUE;
        for (OutlineHeading heading : headings) {
            level = Math.min(level, heading.level);
        }
        return level;
    }

    private int selectionLevel(List<OutlineHeading> headings, int shallowestLevel) {
        if (countAtLevel(headings, shallowestLevel) > 1) {
            return shallowestLevel;
        }
        for (int level = shallowestLevel + 1; level <= 9; level++) {
            if (countAtLevel(headings, level) > 0) {
                return level;
            }
        }
        return shallowestLevel;
    }

    private int countAtLevel(List<OutlineHeading> headings, int level) {
        int count = 0;
        for (OutlineHeading heading : headings) {
            if (heading.level == level) {
                count++;
            }
        }
        return count;
    }

    private List<OutlineHeading> headingsAtLevel(List<OutlineHeading> headings, int level) {
        List<OutlineHeading> result = new ArrayList<OutlineHeading>();
        for (OutlineHeading heading : headings) {
            if (heading.level == level) {
                result.add(heading);
            }
        }
        return result;
    }

    private int outlineSectionEnd(List<OutlineHeading> headings,
                                  OutlineHeading selected,
                                  int documentEnd) {
        boolean afterSelected = false;
        for (OutlineHeading heading : headings) {
            if (heading == selected) {
                afterSelected = true;
                continue;
            }
            if (afterSelected && heading.lineIndex > selected.lineIndex
                    && heading.level <= selected.level) {
                return heading.lineIndex;
            }
        }
        return documentEnd;
    }

    private Heading parseOutlineHeading(String title) {
        Matcher arabic = Pattern.compile("^(\\d+)[.．、]?\\s*(.+)$").matcher(title);
        if (arabic.matches()) {
            return new Heading(arabic.group(1), arabic.group(2).trim(), title);
        }
        Matcher chinese = Pattern.compile("^([一二三四五六七八九十百]+)[、.．]?\\s*(.+)$").matcher(title);
        if (chinese.matches()) {
            return new Heading(chinese.group(1), chinese.group(2).trim(), title);
        }
        return new Heading("", title, title);
    }

    private int findExactLine(String[] lines, String title, int start) {
        for (int i = Math.max(0, start); i < lines.length; i++) {
            if (title.equals(lines[i].trim())) {
                return i;
            }
        }
        return -1;
    }

    private List<ChapterBlock> parseTextChapters(String documentText) {
        String[] lines = documentText.split("\\r?\\n");
        List<Integer> starts = new ArrayList<Integer>();
        List<Heading> headings = new ArrayList<Heading>();
        for (int i = 0; i < lines.length; i++) {
            Heading heading = parseTopHeading(lines[i]);
            if (heading != null && !looksLikeTableOfContentsLine(lines[i])) {
                starts.add(i);
                headings.add(heading);
            }
        }
        // Word tables of contents often repeat the same chapter numbers before the body.
        // Keep the last occurrence of each top-level number, which is normally the real body heading.
        List<Integer> bodyStarts = new ArrayList<Integer>();
        List<Heading> bodyHeadings = new ArrayList<Heading>();
        for (int i = 0; i < starts.size(); i++) {
            boolean repeatedLater = false;
            for (int j = i + 1; j < headings.size(); j++) {
                if (headings.get(i).number.equals(headings.get(j).number)) {
                    repeatedLater = true;
                    break;
                }
            }
            if (!repeatedLater) {
                bodyStarts.add(starts.get(i));
                bodyHeadings.add(headings.get(i));
            }
        }
        starts = bodyStarts;
        headings = bodyHeadings;
        // If no reliable top-level headings exist, expose the complete document as one safe choice.
        if (starts.isEmpty()) {
            List<ChapterBlock> fallback = new ArrayList<ChapterBlock>();
            fallback.add(new ChapterBlock("WHOLE", "", "全文", documentText));
            return fallback;
        }
        List<ChapterBlock> chapters = new ArrayList<ChapterBlock>();
        if (starts.get(0) > 0) {
            String preface = join(lines, 0, starts.get(0));
            if (StringUtils.hasText(preface)) {
                chapters.add(new ChapterBlock("PREFACE", "", "章节前置内容", preface));
            }
        }
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : lines.length;
            Heading heading = headings.get(i);
            chapters.add(new ChapterBlock("CH" + (i + 1), heading.number, heading.title,
                    join(lines, start, end)));
        }
        return chapters;
    }

    private Heading parseTopHeading(String rawLine) {
        String line = rawLine == null ? "" : rawLine.trim();
        Matcher arabic = ARABIC_HEADING.matcher(line);
        if (arabic.matches() && isHeadingLike(arabic.group(2))) {
            return new Heading(arabic.group(1), arabic.group(2).trim());
        }
        Matcher chinese = CHINESE_HEADING.matcher(line);
        if (chinese.matches() && isHeadingLike(chinese.group(2))) {
            return new Heading(chinese.group(1), chinese.group(2).trim());
        }
        return null;
    }

    private boolean isHeadingLike(String title) {
        String text = title == null ? "" : title.trim();
        return StringUtils.hasText(text)
                && text.length() <= 40
                && !text.contains("：")
                && !text.contains(":")
                && !text.endsWith("。")
                && !text.contains("在PC客户端");
    }

    private boolean looksLikeTableOfContentsLine(String line) {
        return line != null && line.trim().matches(".*\\s+\\d+$");
    }

    private String recommendedChapterId(List<ChapterBlock> chapters) {
        ChapterBlock best = null;
        int bestScore = 0;
        for (ChapterBlock chapter : chapters) {
            int score = recommendationScore(chapter.title);
            if (score > bestScore || (score == bestScore && score > 0
                    && best != null && chapter.text.length() > best.text.length())) {
                best = chapter;
                bestScore = score;
            }
        }
        return best == null ? "" : best.id;
    }

    private int recommendationScore(String title) {
        String text = title == null ? "" : title.replaceAll("\\s+", "");
        if (!StringUtils.hasText(text) || containsAny(text, "非功能", "调整因子", "需求背景", "需求目标")) {
            return 0;
        }
        if (text.contains("功能说明")) return 1200;
        if (text.contains("功能需求")) return 1150;
        if (text.contains("需求描述")) return 1100;
        if (text.contains("业务功能")) return 1050;
        if (text.contains("需求细则")) return 1000;
        if (text.contains("需求内容")) return 950;
        if (text.contains("业务需求")) return 900;
        if (text.contains("需求方案")) return 850;
        if (text.contains("方案说明")) return 800;
        if (text.contains("功能")) return 700;
        if (containsAny(text, "业务流程", "数据填报", "数据汇总")) return 600;
        return 0;
    }

    private boolean isPresentInInput(String input, ChapterBlock chapter) {
        if (!StringUtils.hasText(input)) {
            return false;
        }
        String normalizedInput = normalizeForComparison(input);
        String normalizedChapter = normalizeForComparison(chapter.text);
        if (!StringUtils.hasText(normalizedChapter)) {
            return false;
        }
        if (normalizedInput.contains(normalizedChapter)) {
            return true;
        }
        return normalizedInput.length() >= 80
                && normalizedChapter.contains(normalizedInput)
                && normalizedInput.length() >= normalizedChapter.length() * 0.40D;
    }

    private String normalizeForComparison(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").trim();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String join(String[] lines, int start, int end) {
        StringBuilder text = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (StringUtils.hasText(lines[i])) {
                text.append(lines[i].trim()).append(System.lineSeparator());
            }
        }
        return text.toString().trim();
    }

    private String read(String path, String error) {
        String text = readOptional(path);
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(error);
        }
        return text;
    }

    private String readOptional(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        try {
            Path resolved = Paths.get(path);
            return Files.exists(resolved) ? new String(Files.readAllBytes(resolved), StandardCharsets.UTF_8) : "";
        } catch (IOException ex) {
            throw new BusinessException("读取文档章节失败", ex);
        }
    }

    private static class Heading {
        private final String number;
        private final String title;
        private final String originalTitle;
        private Heading(String number, String title) { this(number, title, title); }
        private Heading(String number, String title, String originalTitle) {
            this.number = number;
            this.title = title;
            this.originalTitle = originalTitle;
        }
    }

    private static class OutlineHeading {
        private final int level;
        private final Heading heading;
        private int lineIndex;
        private OutlineHeading(int level, Heading heading) {
            this.level = level;
            this.heading = heading;
        }
    }

    private static class ChapterBlock {
        private final String id;
        private final String number;
        private final String title;
        private final String text;
        private ChapterBlock(String id, String number, String title, String text) {
            this.id = id;
            this.number = number;
            this.title = title;
            this.text = text == null ? "" : text;
        }
    }
}
