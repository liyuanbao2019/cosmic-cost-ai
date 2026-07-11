package com.example.cosmiccostai.service;

import com.example.cosmiccostai.common.BusinessException;
import com.example.cosmiccostai.dto.DocumentChapterItem;
import com.example.cosmiccostai.dto.DocumentChapterSelectionResponse;
import com.example.cosmiccostai.model.ProjectMetadata;
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
        int selectedLength = 0;
        List<DocumentChapterItem> items = new ArrayList<DocumentChapterItem>();
        for (ChapterBlock chapter : chapters) {
            boolean recommended = isRecommended(chapter.title);
            boolean selected = explicitSelection != null
                    ? explicitSelection.contains(chapter.id)
                    : isPresentInInput(currentInput, chapter) || recommended;
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
        response.setSelectedLength(explicitSelection == null && StringUtils.hasText(currentInput)
                ? currentInput.length() : selectedLength);
        return response;
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
        List<Heading> topHeadings = new ArrayList<Heading>();
        for (String outlineLine : outlineLines) {
            String[] parts = outlineLine.split("\\t", 2);
            if (parts.length != 2 || !"1".equals(parts[0]) || !StringUtils.hasText(parts[1])) {
                continue;
            }
            topHeadings.add(parseOutlineHeading(parts[1].trim()));
        }
        if (topHeadings.isEmpty()) {
            return Collections.emptyList();
        }
        String[] lines = documentText.split("\\r?\\n");
        List<Integer> starts = new ArrayList<Integer>();
        List<Heading> located = new ArrayList<Heading>();
        int searchFrom = 0;
        for (Heading heading : topHeadings) {
            int found = findExactLine(lines, heading.originalTitle, searchFrom);
            if (found >= 0) {
                starts.add(found);
                located.add(heading);
                searchFrom = found + 1;
            }
        }
        if (starts.isEmpty()) {
            return Collections.emptyList();
        }
        List<ChapterBlock> chapters = new ArrayList<ChapterBlock>();
        if (starts.get(0) > 0) {
            String preface = join(lines, 0, starts.get(0));
            if (StringUtils.hasText(preface)) {
                chapters.add(new ChapterBlock("PREFACE", "", "章节前置内容", preface));
            }
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : lines.length;
            Heading heading = located.get(i);
            chapters.add(new ChapterBlock("OUTLINE" + (i + 1), heading.number, heading.title,
                    join(lines, starts.get(i), end)));
        }
        return chapters;
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

    private boolean isRecommended(String title) {
        String text = title == null ? "" : title;
        return containsAny(text, "业务功能", "功能需求", "需求细则", "需求内容", "业务需求", "流程", "数据填报", "数据汇总");
    }

    private boolean isPresentInInput(String input, ChapterBlock chapter) {
        if (!StringUtils.hasText(input)) {
            return false;
        }
        String heading = StringUtils.hasText(chapter.number)
                ? chapter.number + (chapter.number.matches("\\d+") ? "." : "、") : "";
        return input.contains(heading + " " + chapter.title)
                || input.contains(heading + chapter.title)
                || input.contains(chapter.title);
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
