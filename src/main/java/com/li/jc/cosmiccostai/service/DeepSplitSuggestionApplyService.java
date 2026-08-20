package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.DeepSplitSuggestion;
import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class DeepSplitSuggestionApplyService {

    private final RuleValidationService ruleValidationService;

    public DeepSplitSuggestionApplyService(RuleValidationService ruleValidationService) {
        this.ruleValidationService = ruleValidationService;
    }

    /**
     * Applies non-conflicting structural suggestions to one immutable deep-review
     * snapshot. Reuse-only suggestions are deliberately left to the dedicated
     * full-table reuse adjustment stage that runs afterwards.
     */
    public ApplyResult apply(List<FunctionSplitRow> originalRows,
                             List<DeepSplitSuggestion> suggestions) {
        List<FunctionSplitRow> rows = copyRows(originalRows);
        List<List<Integer>> origins = new ArrayList<List<Integer>>();
        for (int index = 0; index < rows.size(); index++) {
            origins.add(new ArrayList<Integer>(Collections.singletonList(index + 1)));
        }

        ApplyResult result = new ApplyResult();
        result.rows = rows;
        Set<Integer> reserved = new HashSet<Integer>();
        List<DeepSplitSuggestion> orderedSuggestions = new ArrayList<DeepSplitSuggestion>(suggestions == null
                ? Collections.<DeepSplitSuggestion>emptyList() : suggestions);
        Collections.sort(orderedSuggestions, new Comparator<DeepSplitSuggestion>() {
            @Override
            public int compare(DeepSplitSuggestion left, DeepSplitSuggestion right) {
                if (left == null) return 1;
                if (right == null) return -1;
                int confidence = Double.compare(right.getConfidence(), left.getConfidence());
                if (confidence != 0) return confidence;
                int leftTarget = firstTarget(left);
                int rightTarget = firstTarget(right);
                if (leftTarget != rightTarget) return Integer.compare(leftTarget, rightTarget);
                return safe(left.getId()).compareTo(safe(right.getId()));
            }
        });
        for (DeepSplitSuggestion suggestion : orderedSuggestions) {
            if (suggestion == null || "ADJUST_REUSE_CLASSIFICATION".equals(suggestion.getOperation())) {
                result.skipped++;
                continue;
            }
            List<Integer> targets = normalizedTargets(suggestion.getTargetRowNumbers(), originalRows.size());
            if (targets.isEmpty() || overlaps(targets, reserved)
                    || suggestion.getReplacementRows() == null || suggestion.getReplacementRows().isEmpty()) {
                result.skipped++;
                continue;
            }
            List<Integer> targetIndexes = currentIndexes(origins, targets);
            if (targetIndexes.size() != targets.size()) {
                result.skipped++;
                continue;
            }
            int insertAt = Collections.min(targetIndexes);
            Set<Integer> targetIndexSet = new HashSet<Integer>(targetIndexes);
            List<FunctionSplitRow> replacements = copyRows(suggestion.getReplacementRows());
            List<FunctionSplitRow> nextRows = new ArrayList<FunctionSplitRow>();
            List<List<Integer>> nextOrigins = new ArrayList<List<Integer>>();
            for (int index = 0; index < rows.size(); index++) {
                if (index == insertAt) {
                    for (FunctionSplitRow replacement : replacements) {
                        nextRows.add(replacement);
                        nextOrigins.add(Collections.<Integer>emptyList());
                    }
                }
                if (!targetIndexSet.contains(index)) {
                    nextRows.add(rows.get(index));
                    nextOrigins.add(origins.get(index));
                }
            }
            RuleValidationResponse before = ruleValidationService.validate(rows);
            RuleValidationResponse after = ruleValidationService.validate(nextRows);
            if (after.getErrorCount() > before.getErrorCount()) {
                result.skipped++;
                continue;
            }
            rows = nextRows;
            origins = nextOrigins;
            reserved.addAll(targets);
            result.applied++;
            if (StringUtils.hasText(suggestion.getId())) {
                result.appliedSuggestionIds.add(suggestion.getId());
            }
        }
        result.rows = rows;
        return result;
    }

    private static int firstTarget(DeepSplitSuggestion suggestion) {
        if (suggestion.getTargetRowNumbers() == null || suggestion.getTargetRowNumbers().isEmpty()
                || suggestion.getTargetRowNumbers().get(0) == null) {
            return Integer.MAX_VALUE;
        }
        return suggestion.getTargetRowNumbers().get(0);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private List<Integer> normalizedTargets(List<Integer> values, int size) {
        Set<Integer> unique = new LinkedHashSet<Integer>();
        if (values != null) {
            for (Integer value : values) {
                if (value != null && value >= 1 && value <= size) {
                    unique.add(value);
                }
            }
        }
        List<Integer> result = new ArrayList<Integer>(unique);
        Collections.sort(result);
        return result;
    }

    private boolean overlaps(List<Integer> targets, Set<Integer> reserved) {
        for (Integer target : targets) {
            if (reserved.contains(target)) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> currentIndexes(List<List<Integer>> origins, List<Integer> targets) {
        List<Integer> indexes = new ArrayList<Integer>();
        for (Integer target : targets) {
            for (int index = 0; index < origins.size(); index++) {
                if (origins.get(index).contains(target)) {
                    indexes.add(index);
                    break;
                }
            }
        }
        return indexes;
    }

    private List<FunctionSplitRow> copyRows(List<FunctionSplitRow> source) {
        List<FunctionSplitRow> result = new ArrayList<FunctionSplitRow>();
        for (FunctionSplitRow row : source == null
                ? Collections.<FunctionSplitRow>emptyList() : source) {
            FunctionSplitRow copy = new FunctionSplitRow();
            copy.setFunctionProcessId(row.getFunctionProcessId());
            copy.setProjectName(row.getProjectName());
            copy.setLevel1Module(row.getLevel1Module());
            copy.setLevel2Module(row.getLevel2Module());
            copy.setLevel3Module(row.getLevel3Module());
            copy.setFunctionalUser(row.getFunctionalUser());
            copy.setTriggerEvent(row.getTriggerEvent());
            copy.setFunctionProcess(row.getFunctionProcess());
            copy.setFunctionDescription(row.getFunctionDescription());
            copy.setSubProcessDescription(row.getSubProcessDescription());
            copy.setMovementType(row.getMovementType());
            copy.setDataGroup(row.getDataGroup());
            copy.setDataAttributes(row.getDataAttributes());
            copy.setSequenceDiagram(row.getSequenceDiagram());
            copy.setReuseDegree(row.getReuseDegree());
            copy.setCfp(row.getCfp());
            copy.setEvidence(row.getEvidence());
            copy.setSourceReferences(row.getSourceReferences() == null
                    ? new ArrayList<String>() : new ArrayList<String>(row.getSourceReferences()));
            copy.setSourceHeadingPaths(row.getSourceHeadingPaths() == null
                    ? new ArrayList<String>() : new ArrayList<String>(row.getSourceHeadingPaths()));
            result.add(copy);
        }
        return result;
    }

    public static class ApplyResult {
        private List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>();
        private int applied;
        private int skipped;
        private final List<String> appliedSuggestionIds = new ArrayList<String>();

        public List<FunctionSplitRow> getRows() { return rows; }
        public int getApplied() { return applied; }
        public int getSkipped() { return skipped; }
        public List<String> getAppliedSuggestionIds() { return appliedSuggestionIds; }
    }
}
