package com.example.cosmiccostai.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        if (isExportInfoProbeMiss(ex)) {
            log.debug("Ignored export info probe miss: {}", ex.getMessage());
        } else {
            log.warn("Business exception: {}", ex.getMessage());
        }
        return ResponseEntity.badRequest().body(ApiResponse.<Void>fail(ex.getMessage()));
    }

    private boolean isExportInfoProbeMiss(BusinessException ex) {
        String message = ex.getMessage();
        return message != null && message.endsWith(" has not been exported yet");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex) {
        log.warn("Upload file size exceeded", ex);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.<Void>fail("上传文件超过大小限制"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.<Void>fail("系统异常：" + ex.getMessage()));
    }
}
