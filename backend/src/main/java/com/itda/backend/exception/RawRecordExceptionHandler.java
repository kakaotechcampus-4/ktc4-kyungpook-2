package com.itda.backend.exception;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.itda.backend.global.exception.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

// RawRecord 전용 예외만 처리한다. MissingServletRequestParameterException과
// 그 외 미분류 예외(catch-all)는 global.exception.GlobalExceptionHandler(#3에서 도입)가
// 이미 잡는다 — 여기서 다시 선언하면 Spring이 Ambiguous @ExceptionHandler 오류를 던진다.
@Slf4j
@RestControllerAdvice
@Order(0)
public class RawRecordExceptionHandler {

    @ExceptionHandler(RawRecordValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(RawRecordValidationException e) {
        return ResponseEntity.status(RawRecordErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(RawRecordErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(RawRecordNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(RawRecordNotFoundException e) {
        return ResponseEntity.status(RawRecordErrorCode.NOT_FOUND.getStatus())
                .body(ErrorResponse.of(RawRecordErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(RawRecordErrorCode.FILE_TOO_LARGE.getStatus())
                .body(ErrorResponse.of(RawRecordErrorCode.FILE_TOO_LARGE));
    }

    @ExceptionHandler(RawRecordStorageException.class)
    public ResponseEntity<ErrorResponse> handleStorageFailure(RawRecordStorageException e) {
        log.error("raw record storage failure", e);
        return ResponseEntity.status(RawRecordErrorCode.STORAGE_FAILED.getStatus())
                .body(ErrorResponse.of(RawRecordErrorCode.STORAGE_FAILED));
    }
}
