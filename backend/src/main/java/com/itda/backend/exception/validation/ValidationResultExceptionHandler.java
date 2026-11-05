package com.itda.backend.exception.validation;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.itda.backend.global.exception.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

// ValidationResult 전용 예외만 처리한다. MatchingResultExceptionHandler와 같은 이유로
// @Order를 명시한다 — GlobalExceptionHandler의 Exception.class catch-all이 먼저 매칭되면
// 여기 예외가 400/404 대신 500으로 새어나간다.
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ValidationResultExceptionHandler {

    @ExceptionHandler(ValidationResultValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationResultValidationException e) {
        ValidationResultErrorCode code = e.getErrorCode();
        // 내부 사유(id 가 든 영어 문장)는 로그에만 남기고, 응답에는 ErrorCode 의 사용자용 문구를 보낸다 (backend/AGENTS.md)
        log.info("validation result request rejected code={} reason={}", code.getCode(), e.getMessage());
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }

    @ExceptionHandler(ValidationResultNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ValidationResultNotFoundException e) {
        return ResponseEntity.status(ValidationResultErrorCode.NOT_FOUND.getStatus())
                .body(ErrorResponse.of(ValidationResultErrorCode.NOT_FOUND));
    }
}
