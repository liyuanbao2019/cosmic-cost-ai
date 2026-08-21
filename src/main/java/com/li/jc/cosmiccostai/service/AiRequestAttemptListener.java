package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.AiPartitionProgressItem;

import java.util.List;

/** Receives the real network-attempt boundary for a cancellable AI request. */
public interface AiRequestAttemptListener {
    void onAttempt(int attempt, int maxAttempts, long timeoutSeconds);

    default void onPartitionProgress(List<AiPartitionProgressItem> partitions) {
        // Existing callers only interested in transport attempts remain compatible.
    }
}
