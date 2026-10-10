package com.itda.backend.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.itda.backend.global.exception.ErrorResponse;

// ValidationResult 전용 예외만 처리한다. MatchingResultExceptionHandler와 같은 이유로
// @Order를 명시한다 — GlobalExceptionHandler의 Exception.class catch-all이 먼저 매칭되면
// 여기 예외가 400/404 대신 500으로 새어나간다.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ValidationResultExceptionHandler {

    @ExceptionHandler(ValidationResultValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationResultValidationException e) {
        return ResponseEntity.status(ValidationResultErrorCode.INVALID_REQUEST.getStatus())
                .body(new ErrorResponse("FAIL", ValidationResultErrorCode.INVALID_REQUEST.getCode(), e.getMessage()));
    }

    @ExceptionHandler(ValidationResultNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ValidationResultNotFoundException e) {
        return ResponseEntity.status(ValidationResultErrorCode.NOT_FOUND.getStatus())
                .body(ErrorResponse.of(ValidationResultErrorCode.NOT_FOUND));
    }
}
