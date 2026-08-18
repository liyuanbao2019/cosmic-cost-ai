package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.ExtractedFunction;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps function processes in the order in which their own requirements appear.
 * Movement-level references are only a fallback because a query may read a table
 * defined before the requirement that actually introduces the query operation.
 */
final class RequirementSourceOrder {

    private static final int UNKNOWN_SOURCE_ORDER = Integer.MAX_VALUE;
    private static final Pattern REQUIREMENT_REFERENCE = Pattern.compile("^REQ-L(\\d+)$",
            Pattern.CASE_INSENSITIVE);

    private RequirementSourceOrder() {
    }

    static void sortFunctions(List<ExtractedFunction> functions) {
        if (functions == null || functions.size() < 2) {
            return;
        }
        Collections.sort(functions, new Comparator<ExtractedFunction>() {
            @Override
            public int compare(ExtractedFunction left, ExtractedFunction right) {
                int leftSource = firstReferenceOrder(left == null ? null : left.getSourceReferences());
                int rightSource = firstReferenceOrder(right == null ? null : right.getSourceReferences());
                int sourceComparison = compareKnownSource(leftSource, rightSource);
                if (sourceComparison != 0) {
                    return sourceComparison;
                }
                if (leftSource != UNKNOWN_SOURCE_ORDER && leftSource == rightSource) {
                    return Integer.compare(actionRank(left == null ? null : left.getFunction()),
                            actionRank(right == null ? null : right.getFunction()));
                }
                return 0;
            }
        });
    }

    static void sortRows(List<FunctionSplitRow> rows, List<ExtractedFunction> extractedFunctions) {
        if (rows == null || rows.size() < 2) {
            return;
        }
        FunctionSourceIndex sourceIndex = sourceIndex(extractedFunctions);
        Map<String, ProcessGroup> groupsByKey = new LinkedHashMap<String, ProcessGroup>();
        for (int index = 0; index < rows.size(); index++) {
            FunctionSplitRow row = rows.get(index);
            int sourceOrder = sourceIndex.orderOf(row);
            if (sourceOrder == UNKNOWN_SOURCE_ORDER && row != null) {
                sourceOrder = firstReferenceOrder(row.getSourceReferences());
            }
            String key = processGroupKey(row, index);
            ProcessGroup group = groupsByKey.get(key);
            if (group == null) {
                group = new ProcessGroup(index, sourceOrder,
                        actionRank(row == null ? null : row.getFunctionProcess()));
                groupsByKey.put(key, group);
            } else if (sourceOrder < group.sourceOrder) {
                group.sourceOrder = sourceOrder;
            }
            group.rows.add(row);
        }
        List<ProcessGroup> groups = new ArrayList<ProcessGroup>(groupsByKey.values());
        Collections.sort(groups, new Comparator<ProcessGroup>() {
            @Override
            public int compare(ProcessGroup left, ProcessGroup right) {
                int sourceComparison = compareKnownSource(left.sourceOrder, right.sourceOrder);
                if (sourceComparison != 0) {
                    return sourceComparison;
                }
                if (left.sourceOrder != UNKNOWN_SOURCE_ORDER && left.sourceOrder == right.sourceOrder) {
                    int actionComparison = Integer.compare(left.actionRank, right.actionRank);
                    if (actionComparison != 0) {
                        return actionComparison;
                    }
                }
                return Integer.compare(left.originalIndex, right.originalIndex);
            }
        });
        rows.clear();
        for (ProcessGroup group : groups) {
            rows.addAll(group.rows);
        }
    }

    private static FunctionSourceIndex sourceIndex(List<ExtractedFunction> functions) {
        Map<String, Integer> byModuleAndProcess = new HashMap<String, Integer>();
        Map<String, Integer> byUniqueProcess = new HashMap<String, Integer>();
        Set<String> duplicateProcesses = new HashSet<String>();
        if (functions != null) {
            for (ExtractedFunction function : functions) {
                if (function == null || !StringUtils.hasText(function.getFunction())) {
                    continue;
                }
                int sourceOrder = firstReferenceOrder(function.getSourceReferences());
                if (sourceOrder == UNKNOWN_SOURCE_ORDER) {
                    continue;
                }
                String process = normalized(function.getFunction());
                String module = normalized(function.getModule());
                putEarlier(byModuleAndProcess, moduleProcessKey(module, process), sourceOrder);
                if (byUniqueProcess.containsKey(process)) {
                    duplicateProcesses.add(process);
                } else {
                    byUniqueProcess.put(process, sourceOrder);
                }
            }
        }
        for (String duplicate : duplicateProcesses) {
            byUniqueProcess.remove(duplicate);
        }
        return new FunctionSourceIndex(byModuleAndProcess, byUniqueProcess);
    }

    private static void putEarlier(Map<String, Integer> values, String key, int sourceOrder) {
        Integer existing = values.get(key);
        if (existing == null || sourceOrder < existing) {
            values.put(key, sourceOrder);
        }
    }

    static int firstReferenceOrder(List<String> references) {
        int selected = UNKNOWN_SOURCE_ORDER;
        if (references == null) {
            return selected;
        }
        for (String reference : references) {
            Matcher matcher = REQUIREMENT_REFERENCE.matcher(text(reference).toUpperCase(Locale.ROOT));
            if (!matcher.matches()) {
                continue;
            }
            try {
                selected = Math.min(selected, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // An out-of-range reference is not reliable ordering evidence.
            }
        }
        return selected;
    }

    private static int compareKnownSource(int left, int right) {
        if (left == right) {
            return 0;
        }
        if (left == UNKNOWN_SOURCE_ORDER) {
            return 1;
        }
        if (right == UNKNOWN_SOURCE_ORDER) {
            return -1;
        }
        return Integer.compare(left, right);
    }

    private static int actionRank(String process) {
        String value = text(process);
        if (containsAny(value, "新增", "新建", "创建", "添加", "录入", "导入", "定时生成", "定时汇总")) {
            return 10;
        }
        if (containsAny(value, "查询", "查看", "检索", "列表", "详情")) {
            return 20;
        }
        if (containsAny(value, "修改", "编辑", "更新", "变更")) {
            return 30;
        }
        if (containsAny(value, "删除", "移除", "作废", "注销")) {
            return 40;
        }
        if (containsAny(value, "导出", "下载")) {
            return 50;
        }
        return 60;
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String moduleProcessKey(String module, String process) {
        return module + '\u001f' + process;
    }

    private static String processGroupKey(FunctionSplitRow row, int originalIndex) {
        if (row == null) {
            return "<null>" + originalIndex;
        }
        return normalized(row.getLevel1Module()) + '\u001f'
                + normalized(row.getLevel2Module()) + '\u001f'
                + normalized(row.getLevel3Module()) + '\u001f'
                + normalized(row.getFunctionalUser()) + '\u001f'
                + normalized(row.getTriggerEvent()) + '\u001f'
                + normalized(row.getFunctionProcess());
    }

    private static String normalized(String value) {
        return text(value).toLowerCase(Locale.ROOT);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class FunctionSourceIndex {
        private final Map<String, Integer> byModuleAndProcess;
        private final Map<String, Integer> byUniqueProcess;

        private FunctionSourceIndex(Map<String, Integer> byModuleAndProcess,
                                    Map<String, Integer> byUniqueProcess) {
            this.byModuleAndProcess = byModuleAndProcess;
            this.byUniqueProcess = byUniqueProcess;
        }

        private int orderOf(FunctionSplitRow row) {
            if (row == null) {
                return UNKNOWN_SOURCE_ORDER;
            }
            String process = normalized(row.getFunctionProcess());
            for (String module : new String[]{row.getLevel3Module(), row.getLevel2Module(), row.getLevel1Module()}) {
                Integer value = byModuleAndProcess.get(moduleProcessKey(normalized(module), process));
                if (value != null) {
                    return value;
                }
            }
            Integer unique = byUniqueProcess.get(process);
            return unique == null ? UNKNOWN_SOURCE_ORDER : unique;
        }
    }

    private static final class ProcessGroup {
        private final List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        private final int originalIndex;
        private int sourceOrder;
        private final int actionRank;

        private ProcessGroup(int originalIndex, int sourceOrder, int actionRank) {
            this.originalIndex = originalIndex;
            this.sourceOrder = sourceOrder;
            this.actionRank = actionRank;
        }
    }
}
