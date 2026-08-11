package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.FunctionSplitRow;

/**
 * Defines one shared functional-process identity for validation, AI review and
 * description generation. Function names alone are not unique across modules.
 */
final class FunctionProcessKey {

    private static final String SEPARATOR = "\u001f";

    private FunctionProcessKey() {
    }

    static String of(FunctionSplitRow row) {
        if (row == null) {
            return "";
        }
        return safe(row.getLevel1Module()) + SEPARATOR
                + safe(row.getLevel2Module()) + SEPARATOR
                + safe(row.getLevel3Module()) + SEPARATOR
                + safe(row.getFunctionalUser()) + SEPARATOR
                + safe(row.getTriggerEvent()) + SEPARATOR
                + safe(row.getFunctionProcess());
    }

    static String process(String key) {
        int separator = key == null ? -1 : key.lastIndexOf(SEPARATOR);
        return separator >= 0 ? key.substring(separator + SEPARATOR.length()) : safe(key);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
