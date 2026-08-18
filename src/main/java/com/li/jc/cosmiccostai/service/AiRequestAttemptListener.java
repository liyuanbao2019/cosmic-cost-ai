package com.li.jc.cosmiccostai.service;

/** Receives the real network-attempt boundary for a cancellable AI request. */
public interface AiRequestAttemptListener {
    void onAttempt(int attempt, int maxAttempts, long timeoutSeconds);
}
