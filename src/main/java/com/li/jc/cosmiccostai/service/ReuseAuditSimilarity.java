package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Shared normalization and matching rules for the three reuse audit dimensions. */
final class ReuseAuditSimilarity {

    private ReuseAuditSimilarity() {
    }

    static String comparable(String value) {
        return safe(value).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。、；：“”‘’]+", "");
    }

    static String attributeComparable(String value) {
        List<String> attributes = new ArrayList<String>();
        String normalized = safe(value).replace('（', '、').replace('）', '、')
                .replace('(', '、').replace(')', '、');
        for (String part : normalized.split("[、,，;；|/\\s]+")) {
            String attribute = comparable(part);
            if (StringUtils.hasText(attribute) && !attributes.contains(attribute)) {
                attributes.add(attribute);
            }
        }
        Collections.sort(attributes);
        return String.join("|", attributes);
    }

    static String boundaryKey(FunctionSplitRow row) {
        return comparable(row.getLevel1Module()) + "|" + comparable(row.getLevel2Module()) + "|"
                + comparable(row.getLevel3Module()) + "|" + comparable(row.getFunctionalUser()) + "|"
                + comparable(row.getSequenceDiagram());
    }

    static String exactKey(FunctionSplitRow row) {
        return boundaryKey(row) + "|" + comparable(row.getSubProcessDescription()) + "|"
                + comparable(row.getMovementType()) + "|" + attributeComparable(row.getDataAttributes());
    }

    static String processFingerprint(List<FunctionSplitRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder fingerprint = new StringBuilder(boundaryKey(rows.get(0)));
        for (FunctionSplitRow row : rows) {
            fingerprint.append('|').append(comparable(row.getMovementType()))
                    .append(':').append(comparable(row.getSubProcessDescription()))
                    .append(':').append(comparable(row.getDataGroup()))
                    .append(':').append(attributeComparable(row.getDataAttributes()));
        }
        return fingerprint.toString();
    }

    static String[] pairKeys(FunctionSplitRow row) {
        String boundary = boundaryKey(row);
        String subProcess = comparable(row.getSubProcessDescription());
        String movement = comparable(row.getMovementType());
        String attributes = attributeComparable(row.getDataAttributes());
        return new String[]{
                boundary + "|S+M|" + subProcess + "|" + movement,
                boundary + "|S+A|" + subProcess + "|" + attributes,
                boundary + "|M+A|" + movement + "|" + attributes
        };
    }

    static List<String> pairDimensions(int pairIndex) {
        if (pairIndex == 0) {
            return Arrays.asList("subProcessDescription", "movementType");
        }
        if (pairIndex == 1) {
            return Arrays.asList("subProcessDescription", "dataAttributes");
        }
        return Arrays.asList("movementType", "dataAttributes");
    }

    static boolean hasCompleteAuditElements(FunctionSplitRow row) {
        return StringUtils.hasText(comparable(row.getSubProcessDescription()))
                && StringUtils.hasText(comparable(row.getMovementType()))
                && StringUtils.hasText(attributeComparable(row.getDataAttributes()));
    }

    static boolean isComparableAcrossFunctions(FunctionSplitRow previous, FunctionSplitRow current) {
        String previousObject = processObject(previous.getFunctionProcess());
        String currentObject = processObject(current.getFunctionProcess());
        String previousGroup = comparable(previous.getDataGroup());
        String currentGroup = comparable(current.getDataGroup());
        return (StringUtils.hasText(previousGroup) && previousGroup.equals(currentGroup))
                || (StringUtils.hasText(previousObject) && previousObject.equals(currentObject));
    }

    static String processObject(String process) {
        String value = comparable(process);
        String[] actions = {"查询", "检索", "搜索", "展示", "显示", "数据", "信息", "列表"};
        for (String action : actions) {
            value = value.replace(comparable(action), "");
        }
        return value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
