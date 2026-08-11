package com.li.jc.cosmiccostai.service;

/**
 * Identifies the business purpose of a model call so provider-specific adapters can
 * change transport and output controls without duplicating COSMIC business rules.
 */
public enum AiPromptTask {
    GENERIC,
    EXTRACTION,
    INITIAL_REVIEW,
    REUSE_ADJUSTMENT,
    DEEP_REVIEW,
    FUNCTION_DESCRIPTION
}
