package com.itda.backend.exception.summary;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.itda.backend.exception.validation.ValidationResultExceptionHandler;
import com.itda.backend.global.exception.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

// 요약 전용 예외만 처리한다. ValidationResultExceptionHandler 와 같은 이유로 @Order 를 명시한다 —
// GlobalExceptionHandler 의 Exception.class catch-all 이 먼저 매칭되면 400/404 대신 500 으로 새어나간다.
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SummaryExceptionHandler {

    @ExceptionHandler(SummaryRunValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(SummaryRunValidationException e) {
        return ResponseEntity.status(SummaryErrorCode.INVALID_REQUEST.getStatus())
                .body(new ErrorResponse("FAIL", SummaryErrorCode.INVALID_REQUEST.getCode(), e.getMessage()));
    }

    @ExceptionHandler(SummaryChildNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SummaryChildNotFoundException e) {
        return ResponseEntity.status(SummaryErrorCode.CHILD_NOT_FOUND.getStatus())
                .body(ErrorResponse.of(SummaryErrorCode.CHILD_NOT_FOUND));
    }

    @ExceptionHandler(SummaryException.class)
    public ResponseEntity<ErrorResponse> handleSummaryException(SummaryException e) {
        log.warn("요약 처리 실패: {}", e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getStatus()).body(ErrorResponse.of(e.getErrorCode()));
    }
}
