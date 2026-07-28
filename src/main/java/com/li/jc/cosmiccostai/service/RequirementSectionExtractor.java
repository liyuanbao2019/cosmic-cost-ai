package com.li.jc.cosmiccostai.service;

import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RequirementSectionExtractor {

    private static final Pattern NUMBERED_HEADING_PATTERN = Pattern.compile(
            "^((?:\\d+(?:[.．]\\d+)+[.．]?)|(?:\\d+[.．]))\\s*\\S.{0,100}$");

    private RequirementSectionExtractor() {
    }

    static String extract(String documentText) {
        if (!StringUtils.hasText(documentText)) {
            return "";
        }
        String[] lines = documentText.split("\\r?\\n");
        StringBuilder detail = new StringBuilder();
        boolean collecting = false;
        boolean skippingSupplementalSection = false;
        int requirementSectionLevel = 0;
        int supplementalSectionLevel = 0;
        String requirementSectionTitle = "";
        boolean preserveWholeBusinessSection = false;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (!collecting) {
                if (isRequirementSection(line)) {
                    collecting = true;
                    requirementSectionLevel = sectionHeadingLevel(line);
                    requirementSectionTitle = normalizeSectionTitle(line);
                    preserveWholeBusinessSection = isWholeBusinessSection(requirementSectionTitle);
                }
                continue;
            }
            if (isRequirementEndBoundary(line, requirementSectionLevel)) {
                break;
            }
            int currentLevel = sectionHeadingLevel(line);
            // For standard delivery documents, keep the entire "业务功能需求" chapter.
            // Trying to remove UI/flow sub-sections here has caused real functional
            // descriptions after those sub-sections to be lost. The AI prompt is
            // responsible for ignoring non-functional visual material instead.
            if (!preserveWholeBusinessSection && skippingSupplementalSection) {
                boolean nextPeerOrParentHeading = supplementalSectionLevel > 0
                        && currentLevel > 0
                        && currentLevel <= supplementalSectionLevel;
                if (isFunctionDescriptionSection(line) || nextPeerOrParentHeading) {
                    skippingSupplementalSection = false;
                } else {
                    continue;
                }
            }
            if (!preserveWholeBusinessSection && isSupplementalRequirementSection(line)) {
                skippingSupplementalSection = true;
                supplementalSectionLevel = currentLevel;
                continue;
            }
            if (StringUtils.hasText(line)) {
                detail.append(line).append(System.lineSeparator());
            }
        }
        return refineBroadRequirementSection(detail.toString(), requirementSectionTitle);
    }

    private static String refineBroadRequirementSection(String text, String requirementSectionTitle) {
        String trimmed = text == null ? "" : text.trim();
        if (!StringUtils.hasText(trimmed)) {
            return "";
        }
        if (!("需求细则".equals(requirementSectionTitle) || "需求内容".equals(requirementSectionTitle))) {
            return trimmed;
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
            if (StringUtils.hasText(line)) {
                functionDescription.append(line).append(System.lineSeparator());
            }
        }
        return StringUtils.hasText(functionDescription.toString())
                ? functionDescription.toString().trim()
                : trimmed;
    }

    private static boolean isRequirementEndBoundary(String line, int requirementSectionLevel) {
        String normalized = normalizeSectionTitle(line);
        if (!StringUtils.hasText(normalized) || isRequirementSection(line)) {
            return false;
        }
        if ("非功能性需求".equals(normalized)
                || "非功能需求".equals(normalized)
                || "非功能建设需求".equals(normalized)
                || "附加值调整因子说明".equals(normalized)
                || "调整因子情况说明".equals(normalized)) {
            return true;
        }
        int currentLevel = sectionHeadingLevel(line);
        return requirementSectionLevel > 0
                && currentLevel > 0
                && currentLevel <= requirementSectionLevel
                && isLikelyChapterBoundary(line);
    }

    /**
     * A numbered business action such as "3. 详情展示：..." is not a chapter boundary.
     * Word requirements commonly use this style inside a module, so only short,
     * heading-like lines may close the surrounding business requirement chapter.
     */
    private static boolean isLikelyChapterBoundary(String line) {
        String normalized = normalizeSectionTitle(line);
        return StringUtils.hasText(normalized)
                && normalized.length() <= 60
                && !normalized.contains("：")
                && !normalized.contains(":");
    }

    private static boolean isSupplementalRequirementSection(String line) {
        String normalized = normalizeSectionTitle(line);
        return "功能界面".equals(normalized) || "流程图".equals(normalized);
    }

    private static boolean isRequirementSection(String line) {
        String normalized = normalizeSectionTitle(line);
        return "需求内容".equals(normalized)
                || "需求细则".equals(normalized)
                || "业务功能".equals(normalized)
                || "业务功能需求".equals(normalized)
                || "业务需求".equals(normalized)
                || "功能需求".equals(normalized)
                || "功能性需求".equals(normalized)
                || "需求方案word描述".equalsIgnoreCase(normalized)
                || isFunctionDescriptionSection(line);
    }

    private static boolean isWholeBusinessSection(String normalizedTitle) {
        return "业务功能需求".equals(normalizedTitle) || "业务功能".equals(normalizedTitle);
    }

    private static boolean isFunctionDescriptionSection(String line) {
        String normalized = normalizeSectionTitle(line);
        return "功能描述".equals(normalized) || "需求描述".equals(normalized);
    }

    private static int sectionHeadingLevel(String line) {
        if (!StringUtils.hasText(line)) {
            return 0;
        }
        String value = line.trim();
        if (value.matches("^\\d+[、]\\s*\\S.{0,100}$") && isRequirementSection(value)) {
            return 1;
        }
        Matcher matcher = NUMBERED_HEADING_PATTERN.matcher(value);
        if (matcher.matches()) {
            String prefix = matcher.group(1).replace('．', '.');
            while (prefix.endsWith(".")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            if (StringUtils.hasText(prefix)) {
                return prefix.split("\\.").length;
            }
        }
        if (value.matches("^[一二三四五六七八九十]+[、.．]\\s*\\S.{0,100}$")
                || value.matches("^第[一二三四五六七八九十\\d]+[章节][、.．]?\\s*\\S.{0,100}$")) {
            return 1;
        }
        return 0;
    }

    private static String normalizeSectionTitle(String line) {
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
}
