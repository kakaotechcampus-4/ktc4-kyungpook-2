package com.itda.backend.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.itda.backend.global.exception.ErrorResponse;

// MatchingResult 전용 예외만 처리한다. 그 외 미분류 예외는 global.exception.GlobalExceptionHandler가 잡는다.
// @Order 없으면 Spring이 여러 @RestControllerAdvice 중 어느 걸 먼저 시도할지 빈 등록 순서에
// 맡기는데, GlobalExceptionHandler의 Exception.class catch-all이 이 핸들러보다 먼저 매칭되면
// 여기 예외가 400/404 대신 500으로 새어나간다 — 그래서 이 핸들러를 명시적으로 우선시킨다.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MatchingResultExceptionHandler {

    @ExceptionHandler(MatchingResultValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MatchingResultValidationException e) {
        return ResponseEntity.status(MatchingResultErrorCode.INVALID_REQUEST.getStatus())
                .body(new ErrorResponse("FAIL", MatchingResultErrorCode.INVALID_REQUEST.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MatchingResultNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(MatchingResultNotFoundException e) {
        return ResponseEntity.status(MatchingResultErrorCode.NOT_FOUND.getStatus())
                .body(ErrorResponse.of(MatchingResultErrorCode.NOT_FOUND));
    }
}
