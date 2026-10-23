package com.itda.backend.exception.insight;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.itda.backend.global.exception.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

// 인사이트 전용 예외만 처리한다. SummaryExceptionHandler 와 같은 이유로 @Order 를 명시한다 —
// GlobalExceptionHandler 의 Exception.class catch-all 이 먼저 매칭되면 403/404 대신 500 으로 새어나간다.
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InsightExceptionHandler {

    @ExceptionHandler(InsightException.class)
    public ResponseEntity<ErrorResponse> handleInsightException(InsightException e) {
        log.warn("인사이트 처리 실패: {}", e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getStatus()).body(ErrorResponse.of(e.getErrorCode()));
    }
}
