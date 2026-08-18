package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.RequirementChapterCoverage;
import com.li.jc.cosmiccostai.model.CosmicMovement;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves every REQ-L reference back to the immutable Word heading hierarchy.
 * AI may decide functional-process boundaries, but it is never the authority for
 * level-1/2/3 requirement module names.
 */
@Service
public class RequirementHierarchyService {

    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^\\s*(\\d+(?:[.．]\\d+)*)[.．]?\\s*(\\D.*)$");
    private static final String COVERAGE_FILE = "requirement-extraction-coverage.json";

    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public RequirementHierarchyService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void enrichFunctions(String projectId,
                                String requirementDetail,
                                List<ExtractedFunction> functions) {
        Map<String, HeadingPath> references = resolveReferencePaths(projectId, requirementDetail);
        for (ExtractedFunction function : functions == null
                ? Collections.<ExtractedFunction>emptyList() : functions) {
            if (function == null) {
                continue;
            }
            apply(function, paths(function.getSourceReferences(), references));
            for (CosmicMovement movement : function.getMovements() == null
                    ? Collections.<CosmicMovement>emptyList() : function.getMovements()) {
                if (movement == null) {
                    continue;
                }
                List<HeadingPath> movementPaths = paths(movement.getSourceReferences(), references);
                if (movementPaths.isEmpty()) {
                    movement.setLevel1Module(function.getLevel1Module());
                    movement.setLevel2Module(function.getLevel2Module());
                    movement.setLevel3Module(function.getLevel3Module());
                    movement.setSourceHeadingPaths(copy(function.getSourceHeadingPaths()));
                } else {
                    apply(movement, movementPaths);
                }
            }
        }
    }

    /** Repairs stored rows from older projects without another model request. */
    public void enrichRows(ProjectMetadata metadata, List<FunctionSplitRow> rows) {
        if (metadata == null || !StringUtils.hasText(metadata.getId())
                || !StringUtils.hasText(metadata.getRequirementDetailPath())) {
            return;
        }
        Path requirementPath = Paths.get(metadata.getRequirementDetailPath());
        if (!Files.exists(requirementPath)) {
            return;
        }
        final String requirementDetail;
        try {
            requirementDetail = new String(Files.readAllBytes(requirementPath), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BusinessException("读取需求正文失败：" + requirementPath, ex);
        }
        Map<String, HeadingPath> references = resolveReferencePaths(metadata.getId(), requirementDetail);
        for (FunctionSplitRow row : rows == null ? Collections.<FunctionSplitRow>emptyList() : rows) {
            if (row == null) {
                continue;
            }
            List<HeadingPath> resolved = paths(row.getSourceReferences(), references);
            HeadingPath primary = firstNonEmpty(resolved);
            if (primary == null) {
                continue;
            }
            row.setLevel1Module(primary.level1);
            row.setLevel2Module(primary.level2);
            row.setLevel3Module(primary.level3);
            row.setSourceHeadingPaths(labels(resolved));
        }
    }

    public String annotateRequirement(String projectId, String requirementDetail) {
        Map<String, HeadingPath> paths = resolveReferencePaths(projectId, requirementDetail);
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (String raw : safe(requirementDetail).split("\\R")) {
            String line = safe(raw);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            String reference = reference(++index);
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append('[').append(reference).append(']');
            HeadingPath path = paths.get(reference);
            if (path != null && StringUtils.hasText(path.fullPath)) {
                builder.append("[原文路径：").append(path.fullPath).append(']');
            }
            builder.append(' ').append(line);
        }
        return builder.toString();
    }

    /**
     * Persists an explicit outcome for every top-level source chapter. Missing
     * references are review warnings only: a post-AI coverage check must never
     * discard an otherwise usable split result.
     */
    public List<RequirementChapterCoverage> validateAndWriteCoverage(String projectId,
                                                                     String requirementDetail,
                                                                     List<ExtractedFunction> functions) {
        Map<String, HeadingPath> referencePaths = resolveReferencePaths(projectId, requirementDetail);
        Map<String, String> lineText = requirementLines(requirementDetail);
        Set<String> used = usedReferences(functions);
        Map<String, ChapterAccumulator> chapters = new LinkedHashMap<String, ChapterAccumulator>();
        for (Map.Entry<String, HeadingPath> entry : referencePaths.entrySet()) {
            HeadingPath path = entry.getValue();
            if (path == null || !StringUtils.hasText(path.level1)) {
                continue;
            }
            ChapterAccumulator chapter = chapters.get(path.level1);
            if (chapter == null) {
                chapter = new ChapterAccumulator(path.level1);
                chapters.put(path.level1, chapter);
            }
            chapter.sourceReferences.add(entry.getKey());
            chapter.text.append(' ').append(safe(lineText.get(entry.getKey())));
            if (used.contains(entry.getKey())) {
                chapter.usedReferences.add(entry.getKey());
            }
        }

        List<RequirementChapterCoverage> result = new ArrayList<RequirementChapterCoverage>();
        for (ChapterAccumulator chapter : chapters.values()) {
            RequirementChapterCoverage item = new RequirementChapterCoverage();
            item.setHeadingPath(chapter.heading);
            item.setSourceReferences(new ArrayList<String>(chapter.sourceReferences));
            item.setUsedReferences(new ArrayList<String>(chapter.usedReferences));
            if (!chapter.usedReferences.isEmpty()) {
                item.setStatus("COVERED");
                item.setReason("该章节已有功能过程或数据移动引用原文证据");
            } else if (isConstraintChapter(chapter.heading, chapter.text.toString())) {
                item.setStatus("CONSTRAINT_NOT_INDEPENDENT");
                item.setReason("该章节属于权限范围、存储留存或非功能约束，不强行新增COSMIC功能过程，但保留显式覆盖记录");
            } else {
                item.setStatus("MISSING_REVIEW");
                item.setReason("原文业务章节没有任何功能过程或数据移动引用，已保留为人工审核提醒，不阻断本次拆分");
            }
            result.add(item);
        }
        writeCoverage(projectId, result);
        return result;
    }

    Map<String, HeadingPath> resolveReferencePaths(String projectId, String requirementDetail) {
        List<OutlineHeading> outline = readOutline(projectId);
        List<String> detailLines = nonEmptyLines(requirementDetail);
        Map<String, HeadingPath> result = new LinkedHashMap<String, HeadingPath>();
        if (detailLines.isEmpty()) {
            return result;
        }

        int rootIndex = findRequirementRoot(outline, detailLines);
        int cursor = rootIndex >= 0 ? rootIndex : 0;
        Integer rootLevel = rootIndex >= 0 ? outline.get(rootIndex).level : null;
        TreeMap<Integer, String> stack = new TreeMap<Integer, String>();

        for (int i = 0; i < detailLines.size(); i++) {
            String line = detailLines.get(i);
            int matched = findOutlineHeading(outline, cursor, line);
            if (matched >= 0) {
                OutlineHeading heading = outline.get(matched);
                removeAtOrBelow(stack, heading.level);
                stack.put(heading.level, heading.title);
                cursor = matched + 1;
                if (isRequirementContainer(heading.title)) {
                    rootLevel = heading.level;
                } else if (rootLevel == null) {
                    rootLevel = Math.max(0, heading.level - 1);
                }
            } else if (outline.isEmpty()) {
                InlineHeading heading = inlineHeading(line);
                if (heading != null) {
                    removeAtOrBelow(stack, heading.level);
                    stack.put(heading.level, line);
                    if (isRequirementContainer(line)) {
                        rootLevel = heading.level;
                    } else if (rootLevel == null) {
                        rootLevel = Math.max(0, heading.level - 1);
                    }
                }
            }
            result.put(reference(i + 1), buildPath(stack, rootLevel));
        }
        return result;
    }

    private void apply(ExtractedFunction function, List<HeadingPath> paths) {
        HeadingPath primary = firstNonEmpty(paths);
        if (primary == null) {
            return;
        }
        function.setLevel1Module(primary.level1);
        function.setLevel2Module(primary.level2);
        function.setLevel3Module(primary.level3);
        function.setSourceHeadingPaths(labels(paths));
        String exactModule = deepest(primary);
        if (StringUtils.hasText(exactModule)) {
            function.setModule(exactModule);
        }
    }

    private void apply(CosmicMovement movement, List<HeadingPath> paths) {
        HeadingPath primary = firstNonEmpty(paths);
        if (primary == null) {
            return;
        }
        movement.setLevel1Module(primary.level1);
        movement.setLevel2Module(primary.level2);
        movement.setLevel3Module(primary.level3);
        movement.setSourceHeadingPaths(labels(paths));
    }

    private List<HeadingPath> paths(List<String> references, Map<String, HeadingPath> resolved) {
        List<HeadingPath> result = new ArrayList<HeadingPath>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String reference : references == null ? Collections.<String>emptyList() : references) {
            HeadingPath path = resolved.get(safe(reference).toUpperCase());
            if (path != null && seen.add(path.key())) {
                result.add(path);
            }
        }
        return result;
    }

    private HeadingPath firstNonEmpty(List<HeadingPath> paths) {
        for (HeadingPath path : paths) {
            if (path != null && StringUtils.hasText(path.level1)) {
                return path;
            }
        }
        return null;
    }

    private List<String> labels(List<HeadingPath> paths) {
        List<String> result = new ArrayList<String>();
        for (HeadingPath path : paths) {
            if (path != null && StringUtils.hasText(path.fullPath) && !result.contains(path.fullPath)) {
                result.add(path.fullPath);
            }
        }
        return result;
    }

    private HeadingPath buildPath(TreeMap<Integer, String> stack, Integer rootLevel) {
        if (rootLevel == null) {
            return HeadingPath.empty();
        }
        List<String> modules = new ArrayList<String>();
        List<String> full = new ArrayList<String>();
        for (Map.Entry<Integer, String> entry : stack.entrySet()) {
            if (entry.getKey() < rootLevel) {
                continue;
            }
            String title = displayTitle(entry.getValue());
            if (!StringUtils.hasText(title)) {
                continue;
            }
            if (entry.getKey() == rootLevel) {
                full.add(title);
                continue;
            }
            if (isSupplementalHeading(title)) {
                continue;
            }
            full.add(title);
            if (modules.size() < 3) {
                modules.add(title);
            }
        }
        return new HeadingPath(value(modules, 0), value(modules, 1), value(modules, 2), joinPath(full));
    }

    private List<OutlineHeading> readOutline(String projectId) {
        Path path = fileStorageService.resolveProjectDir(projectId).resolve("document-outline.tsv");
        if (!Files.exists(path)) {
            return Collections.emptyList();
        }
        List<OutlineHeading> result = new ArrayList<OutlineHeading>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\\t", 2);
                if (parts.length == 2 && parts[0].matches("[1-9]") && StringUtils.hasText(parts[1])) {
                    result.add(new OutlineHeading(Integer.parseInt(parts[0]), parts[1].trim()));
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("读取原文标题树失败：" + path, ex);
        }
        return result;
    }

    private int findRequirementRoot(List<OutlineHeading> outline, List<String> detailLines) {
        Set<String> detail = new LinkedHashSet<String>();
        for (String line : detailLines) {
            detail.add(normalizeTitle(line));
        }
        for (int i = 0; i < outline.size(); i++) {
            if (isRequirementContainer(outline.get(i).title)
                    && detail.contains(normalizeTitle(outline.get(i).title))) {
                return i;
            }
        }
        return -1;
    }

    private int findOutlineHeading(List<OutlineHeading> outline, int cursor, String line) {
        String expected = normalizeTitle(line);
        for (int i = Math.max(0, cursor); i < outline.size(); i++) {
            if (expected.equals(normalizeTitle(outline.get(i).title))) {
                return i;
            }
        }
        return -1;
    }

    private InlineHeading inlineHeading(String line) {
        Matcher matcher = NUMBERED_HEADING.matcher(safe(line));
        if (!matcher.matches() || !StringUtils.hasText(matcher.group(2))) {
            return null;
        }
        return new InlineHeading(matcher.group(1).split("[.．]").length);
    }

    private void removeAtOrBelow(TreeMap<Integer, String> stack, int level) {
        List<Integer> remove = new ArrayList<Integer>();
        for (Integer key : stack.keySet()) {
            if (key >= level) {
                remove.add(key);
            }
        }
        for (Integer key : remove) {
            stack.remove(key);
        }
    }

    private boolean isRequirementContainer(String title) {
        String value = normalizeTitle(displayTitle(title));
        return isRequirementContainerText(value);
    }

    private boolean isRequirementContainerText(String value) {
        return "需求描述".equals(value)
                || "需求细则".equals(value)
                || "业务需求".equals(value)
                || "功能需求".equals(value)
                || "业务功能".equals(value)
                || "业务功能需求".equals(value);
    }

    private boolean isSupplementalHeading(String title) {
        String value = normalizeTitle(title);
        return "功能描述".equals(value)
                || "功能界面".equals(value)
                || "流程图".equals(value)
                || "时序图".equals(value);
    }

    private boolean isConstraintChapter(String heading, String body) {
        String title = safe(heading);
        String text = safe(body);
        if (containsAny(title, "性能", "兼容", "部署", "培训", "容灾", "备份")) {
            return true;
        }
        if (containsAny(title, "权限", "分权", "安全")
                && containsAny(text, "仅能", "可查看", "不得", "无跨", "过滤", "隔离", "范围")) {
            return true;
        }
        return containsAny(title, "存储", "留存", "审计")
                && containsAny(text, "永久", "时效", "留存内容", "保留", "不支持人工删除");
    }

    private Set<String> usedReferences(List<ExtractedFunction> functions) {
        Set<String> result = new LinkedHashSet<String>();
        for (ExtractedFunction function : functions == null
                ? Collections.<ExtractedFunction>emptyList() : functions) {
            if (function == null) {
                continue;
            }
            addReferences(result, function.getSourceReferences());
            for (CosmicMovement movement : function.getMovements() == null
                    ? Collections.<CosmicMovement>emptyList() : function.getMovements()) {
                if (movement != null) {
                    addReferences(result, movement.getSourceReferences());
                }
            }
        }
        return result;
    }

    private void addReferences(Set<String> target, List<String> references) {
        for (String reference : references == null ? Collections.<String>emptyList() : references) {
            if (StringUtils.hasText(reference)) {
                target.add(reference.trim().toUpperCase());
            }
        }
    }

    private Map<String, String> requirementLines(String requirementDetail) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        int index = 0;
        for (String raw : safe(requirementDetail).split("\\R")) {
            String line = safe(raw);
            if (StringUtils.hasText(line)) {
                result.put(reference(++index), line);
            }
        }
        return result;
    }

    private List<String> nonEmptyLines(String value) {
        List<String> result = new ArrayList<String>();
        for (String raw : safe(value).split("\\R")) {
            String line = safe(raw);
            if (StringUtils.hasText(line)) {
                result.add(line);
            }
        }
        return result;
    }

    private void writeCoverage(String projectId, List<RequirementChapterCoverage> coverage) {
        Path path = fileStorageService.resolveProjectDir(projectId).resolve(COVERAGE_FILE);
        try {
            objectMapper.writeValue(path.toFile(), coverage);
        } catch (IOException ex) {
            throw new BusinessException("写入章节覆盖结果失败：" + path, ex);
        }
    }

    private String displayTitle(String title) {
        String value = safe(title);
        Matcher matcher = NUMBERED_HEADING.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        String sectionNumber = matcher.group(1);
        String remainder = matcher.group(2).trim();
        // Multi-level numbers are document section numbers. A single leading
        // integer is stripped only for known container headings (for example
        // "3需求描述"), so a real title such as "2026年运维管理扩容" stays intact.
        if (sectionNumber.contains(".")
                || sectionNumber.contains("．")
                || isRequirementContainerText(normalizeTitle(remainder))) {
            return remainder;
        }
        return value;
    }

    private String normalizeTitle(String title) {
        return safe(title).replace('．', '.').replaceAll("\\s+", "").trim();
    }

    private String deepest(HeadingPath path) {
        if (StringUtils.hasText(path.level3)) return path.level3;
        if (StringUtils.hasText(path.level2)) return path.level2;
        return path.level1;
    }

    private String value(List<String> values, int index) {
        return index < values.size() ? values.get(index) : "";
    }

    private String joinPath(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (!StringUtils.hasText(value)) continue;
            if (result.length() > 0) result.append(" > ");
            result.append(value);
        }
        return result.toString();
    }

    private String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append('、');
            result.append(value);
        }
        return result.toString();
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (safe(text).contains(value)) return true;
        }
        return false;
    }

    private List<String> copy(List<String> values) {
        return values == null ? new ArrayList<String>() : new ArrayList<String>(values);
    }

    private String reference(int index) {
        return String.format(java.util.Locale.ROOT, "REQ-L%04d", index);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    static final class HeadingPath {
        final String level1;
        final String level2;
        final String level3;
        final String fullPath;

        HeadingPath(String level1, String level2, String level3, String fullPath) {
            this.level1 = level1;
            this.level2 = level2;
            this.level3 = level3;
            this.fullPath = fullPath;
        }

        static HeadingPath empty() {
            return new HeadingPath("", "", "", "");
        }

        String key() {
            return level1 + '\u001f' + level2 + '\u001f' + level3;
        }
    }

    private static final class OutlineHeading {
        private final int level;
        private final String title;

        private OutlineHeading(int level, String title) {
            this.level = level;
            this.title = title;
        }
    }

    private static final class InlineHeading {
        private final int level;

        private InlineHeading(int level) {
            this.level = level;
        }
    }

    private static final class ChapterAccumulator {
        private final String heading;
        private final List<String> sourceReferences = new ArrayList<String>();
        private final List<String> usedReferences = new ArrayList<String>();
        private final StringBuilder text = new StringBuilder();

        private ChapterAccumulator(String heading) {
            this.heading = heading;
        }
    }
}
