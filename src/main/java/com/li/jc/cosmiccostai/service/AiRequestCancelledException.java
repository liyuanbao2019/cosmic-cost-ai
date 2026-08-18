package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.common.BusinessException;

/** Signals a user-requested cancellation rather than an AI or network failure. */
public class AiRequestCancelledException extends BusinessException {
    public AiRequestCancelledException(String message) {
        super(message);
    }
}
