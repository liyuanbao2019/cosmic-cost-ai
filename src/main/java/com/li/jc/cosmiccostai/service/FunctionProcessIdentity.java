package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.CosmicMovement;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Maintains a stable functional-process identity independently from display
 * hierarchy. Movement evidence may legitimately come from different headings;
 * that must never split one E/R/W/X sequence into multiple processes.
 */
final class FunctionProcessIdentity {

    private FunctionProcessIdentity() {
    }

    static void ensureFunctions(List<ExtractedFunction> functions) {
        Set<String> used = new HashSet<String>();
        int sequence = 1;
        for (ExtractedFunction function : functions == null
                ? new ArrayList<ExtractedFunction>() : functions) {
            if (function == null) {
                continue;
            }
            String id = text(function.getFunctionProcessId());
            if (!StringUtils.hasText(id) || used.contains(normalized(id))) {
                do {
                    id = String.format(Locale.ROOT, "FP-%04d", sequence++);
                } while (used.contains(normalized(id)));
                function.setFunctionProcessId(id);
            }
            used.add(normalized(id));
        }
    }

    static void assignRows(List<FunctionSplitRow> rows, List<ExtractedFunction> functions) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        ensureFunctions(functions);
        List<Candidate> candidates = candidates(functions);
        Set<String> usedIds = new HashSet<String>();
        for (Candidate candidate : candidates) {
            usedIds.add(normalized(candidate.id));
        }
        for (FunctionSplitRow row : rows) {
            if (row != null && StringUtils.hasText(row.getFunctionProcessId())) {
                usedIds.add(normalized(row.getFunctionProcessId()));
            }
        }

        for (FunctionSplitRow row : rows) {
            if (row == null || StringUtils.hasText(row.getFunctionProcessId())) {
                continue;
            }
            Candidate selected = select(row, candidates);
            if (selected != null) {
                row.setFunctionProcessId(selected.id);
            }
        }

        int legacySequence = 1;
        String activeId = "";
        String activeSignature = "";
        String previousType = "";
        for (FunctionSplitRow row : rows) {
            if (row == null) {
                continue;
            }
            if (StringUtils.hasText(row.getFunctionProcessId())) {
                activeId = row.getFunctionProcessId();
                activeSignature = signature(row);
                previousType = text(row.getMovementType()).toUpperCase(Locale.ROOT);
                continue;
            }
            String signature = signature(row);
            boolean newProcess = !StringUtils.hasText(activeId)
                    || !signature.equals(activeSignature)
                    || ("E".equalsIgnoreCase(row.getMovementType()) && terminal(previousType));
            if (newProcess) {
                do {
                    activeId = String.format(Locale.ROOT, "FP-LEGACY-%04d", legacySequence++);
                } while (usedIds.contains(normalized(activeId)));
                usedIds.add(normalized(activeId));
                activeSignature = signature;
            }
            row.setFunctionProcessId(activeId);
            previousType = text(row.getMovementType()).toUpperCase(Locale.ROOT);
        }
    }

    private static Candidate select(FunctionSplitRow row, List<Candidate> candidates) {
        Candidate selected = null;
        int selectedScore = Integer.MIN_VALUE;
        int sameNameCount = 0;
        String process = normalized(row.getFunctionProcess());
        for (Candidate candidate : candidates) {
            if (!process.equals(candidate.process)) {
                continue;
            }
            sameNameCount++;
            int score = candidate.score(row);
            if (score > selectedScore) {
                selected = candidate;
                selectedScore = score;
            }
        }
        if (sameNameCount == 1 || selectedScore > 0) {
            return selected;
        }
        return null;
    }

    private static List<Candidate> candidates(List<ExtractedFunction> functions) {
        List<Candidate> result = new ArrayList<Candidate>();
        for (ExtractedFunction function : functions == null
                ? new ArrayList<ExtractedFunction>() : functions) {
            if (function != null && StringUtils.hasText(function.getFunctionProcessId())
                    && StringUtils.hasText(function.getFunction())) {
                result.add(new Candidate(function));
            }
        }
        return result;
    }

    private static String signature(FunctionSplitRow row) {
        return normalized(row.getFunctionalUser()) + '\u001f'
                + normalized(row.getTriggerEvent()) + '\u001f'
                + normalized(row.getFunctionProcess());
    }

    private static boolean terminal(String type) {
        return "W".equals(type) || "X".equals(type);
    }

    private static String normalized(String value) {
        return text(value).toLowerCase(Locale.ROOT);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Candidate {
        private final String id;
        private final String process;
        private final String level1;
        private final String level2;
        private final String level3;
        private final String functionalUser;
        private final String triggerEvent;
        private final Set<String> references = new LinkedHashSet<String>();

        private Candidate(ExtractedFunction function) {
            this.id = text(function.getFunctionProcessId());
            this.process = normalized(function.getFunction());
            this.level1 = normalized(function.getLevel1Module());
            this.level2 = normalized(function.getLevel2Module());
            this.level3 = normalized(function.getLevel3Module());
            this.functionalUser = normalized(function.getFunctionalUser());
            this.triggerEvent = normalized(function.getTriggerEvent());
            addReferences(function.getSourceReferences());
            for (CosmicMovement movement : function.getMovements() == null
                    ? new ArrayList<CosmicMovement>() : function.getMovements()) {
                if (movement != null) {
                    addReferences(movement.getSourceReferences());
                }
            }
        }

        private int score(FunctionSplitRow row) {
            int score = 0;
            for (String reference : row.getSourceReferences() == null
                    ? new ArrayList<String>() : row.getSourceReferences()) {
                if (references.contains(normalized(reference))) {
                    score += 1000;
                }
            }
            score += same(level1, row.getLevel1Module()) ? 30 : 0;
            score += same(level2, row.getLevel2Module()) ? 20 : 0;
            score += same(level3, row.getLevel3Module()) ? 10 : 0;
            score += same(functionalUser, row.getFunctionalUser()) ? 5 : 0;
            score += same(triggerEvent, row.getTriggerEvent()) ? 5 : 0;
            return score;
        }

        private boolean same(String expected, String actual) {
            return StringUtils.hasText(expected) && expected.equals(normalized(actual));
        }

        private void addReferences(List<String> values) {
            for (String value : values == null ? new ArrayList<String>() : values) {
                if (StringUtils.hasText(value)) {
                    references.add(normalized(value));
                }
            }
        }
    }
}
