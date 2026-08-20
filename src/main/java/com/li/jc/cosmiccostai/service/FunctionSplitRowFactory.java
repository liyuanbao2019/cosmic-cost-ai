package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.CosmicMovement;
import com.li.jc.cosmiccostai.model.CosmicResult;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class FunctionSplitRowFactory {

    private static final String PENDING = "待确认";

    private FunctionSplitRowFactory() {
    }

    static List<FunctionSplitRow> create(ProjectMetadata metadata, CosmicResult result) {
        List<FunctionSplitRow> rows = detailedRows(metadata, result);
        if (!rows.isEmpty()) {
            return rows;
        }
        addFallbackRows(rows, metadata, result, "E", result.getEntry(), defaultEntryDescription(result.getFunction()));
        addFallbackRows(rows, metadata, result, "R", result.getRead(),
                "读取" + businessObject(result.getFunction()) + "所需业务数据");
        addFallbackRows(rows, metadata, result, "W", result.getWrite(),
                "写入" + businessObject(result.getFunction()) + "业务结果");
        addFallbackRows(rows, metadata, result, "X", result.getExit(), defaultExitDescription(result.getFunction()));
        return rows;
    }

    private static List<FunctionSplitRow> detailedRows(ProjectMetadata metadata, CosmicResult result) {
        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        if (result.getMovements() == null) {
            return rows;
        }
        MovementDataAttributeNormalizer.normalize(result.getMovements());
        boolean hasEntry = false;
        boolean hasResult = false;
        for (CosmicMovement movement : result.getMovements()) {
            if (movement == null) {
                continue;
            }
            String type = normalizeType(movement.getType());
            if (!StringUtils.hasText(type)) {
                continue;
            }
            hasEntry = hasEntry || "E".equals(type);
            hasResult = hasResult || "W".equals(type) || "X".equals(type);
            FunctionSplitRow row = baseRow(metadata, result, movement);
            row.setMovementType(type);
            row.setSubProcessDescription(firstText(movement.getDescription(),
                    fallbackDescription(type, result.getFunction())));
            row.setDataGroup(firstText(movement.getDataGroup(), PENDING));
            row.setDataAttributes(firstText(movement.getDataAttributes(), PENDING));
            row.setSequenceDiagram(safe(movement.getSequenceDiagram()));
            row.setEvidence(firstText(movement.getEvidence(), result.getEvidence()));
            row.setSourceReferences(copyReferences(movement.getSourceReferences(), result.getSourceReferences()));
            MovementDescriptionNormalizer.normalize(row);
            rows.add(row);
        }
        if (!hasEntry || !hasResult) {
            rows.clear();
        }
        return rows;
    }

    private static void addFallbackRows(List<FunctionSplitRow> rows,
                                        ProjectMetadata metadata,
                                        CosmicResult result,
                                        String type,
                                        int count,
                                        String description) {
        for (int i = 0; i < count; i++) {
            FunctionSplitRow row = baseRow(metadata, result, null);
            row.setSubProcessDescription(description);
            row.setMovementType(type);
            row.setDataGroup(PENDING);
            row.setDataAttributes(PENDING);
            rows.add(row);
        }
    }

    private static FunctionSplitRow baseRow(ProjectMetadata metadata,
                                            CosmicResult result,
                                            CosmicMovement movement) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setFunctionProcessId(result.getFunctionProcessId());
        row.setProjectName(metadata.getName());
        // Module columns identify the functional process, not individual data
        // movements. Movement-specific paths remain in sourceHeadingPaths only.
        row.setLevel1Module(firstText(result.getLevel1Module(), result.getModule()));
        row.setLevel2Module(safe(result.getLevel2Module()));
        row.setLevel3Module(firstText(result.getLevel3Module(), result.getFunction()));
        row.setFunctionalUser(firstText(result.getFunctionalUser(), defaultFunctionalUser(result.getSequenceDiagram())));
        row.setTriggerEvent(firstText(result.getTriggerEvent(), result.getFunction()));
        row.setFunctionProcess(result.getFunction());
        row.setFunctionDescription(result.getDescription());
        row.setEvidence(result.getEvidence());
        row.setSourceReferences(copyReferences(result.getSourceReferences(), null));
        row.setSourceHeadingPaths(copyReferences(
                movement == null ? null : movement.getSourceHeadingPaths(), result.getSourceHeadingPaths()));
        row.setReuseDegree("新增");
        row.setCfp(1);
        return row;
    }

    private static List<String> copyReferences(List<String> preferred, List<String> fallback) {
        List<String> source = preferred != null && !preferred.isEmpty() ? preferred : fallback;
        return source == null ? new ArrayList<String>() : new ArrayList<String>(source);
    }

    static String defaultEntryDescription(String function) {
        String value = safe(function);
        if (!StringUtils.hasText(value)) {
            return "发起业务操作";
        }
        if (startsWithAny(value, "接收", "发起", "提交", "输入", "录入", "导入", "上传", "上报")) {
            return value;
        }
        int outboundVerb = firstVerbEnd(value, "发送", "推送", "同步至", "调用");
        if (outboundVerb >= 0) {
            String object = stripRequestSuffix(value.substring(outboundVerb));
            return "发起" + (StringUtils.hasText(object) ? object : businessObject(value));
        }
        String action = stripRequestSuffix(value);
        return "发起" + (StringUtils.hasText(action) ? action : "业务操作");
    }

    private static String defaultExitDescription(String function) {
        String value = safe(function);
        if (containsAny(value, "发送", "推送", "同步至")) {
            return value;
        }
        String object = value.startsWith("接收") ? value.substring(2) : value;
        object = stripRequestSuffix(object);
        return "返回" + (StringUtils.hasText(object) ? object : "业务") + "处理结果";
    }

    private static String fallbackDescription(String type, String function) {
        if ("E".equals(type)) {
            return defaultEntryDescription(function);
        }
        if ("R".equals(type)) {
            return "读取" + businessObject(function) + "所需业务数据";
        }
        if ("W".equals(type)) {
            return "写入" + businessObject(function) + "业务结果";
        }
        return defaultExitDescription(function);
    }

    private static String businessObject(String function) {
        String value = safe(function);
        int outboundVerb = firstVerbEnd(value, "发送", "推送", "同步至", "调用");
        if (outboundVerb >= 0) {
            value = value.substring(outboundVerb);
        }
        value = stripRequestSuffix(value);
        return StringUtils.hasText(value) ? value : "业务";
    }

    private static String defaultFunctionalUser(String sequenceDiagram) {
        String firstStep = safe(sequenceDiagram);
        int separator = firstStep.indexOf(';');
        if (separator >= 0) {
            firstStep = firstStep.substring(0, separator);
        }
        int arrow = firstStep.indexOf("->");
        if (arrow > 0 && arrow < firstStep.length() - 2) {
            return "发起者：" + firstStep.substring(0, arrow).trim()
                    + "\n接收者：" + firstStep.substring(arrow + 2).trim();
        }
        return "发起者：待确认\n接收者：本系统";
    }

    private static int firstVerbEnd(String value, String... verbs) {
        int selected = -1;
        int length = 0;
        for (String verb : verbs) {
            int index = value.indexOf(verb);
            if (index >= 0 && (selected < 0 || index < selected)) {
                selected = index;
                length = verb.length();
            }
        }
        return selected < 0 ? -1 : selected + length;
    }

    private static String stripRequestSuffix(String value) {
        String result = safe(value);
        while (result.endsWith("请求")) {
            result = result.substring(0, result.length() - 2).trim();
        }
        return result;
    }

    private static String normalizeType(String type) {
        String value = safe(type).toUpperCase(Locale.ROOT);
        return "E".equals(value) || "R".equals(value) || "W".equals(value) || "X".equals(value) ? value : "";
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim() : safe(fallback);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
