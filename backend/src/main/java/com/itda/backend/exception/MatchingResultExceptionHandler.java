package com.itda.backend.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.itda.backend.global.exception.ErrorResponse;

// MatchingResult 전용 예외만 처리한다. 그 외 미분류 예외는 global.exception.GlobalExceptionHandler가 잡는다.
@RestControllerAdvice
public class MatchingResultExceptionHandler {

    @ExceptionHandler(MatchingResultValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MatchingResultValidationException e) {
        return ResponseEntity.status(MatchingResultErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(MatchingResultErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(MatchingResultNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(MatchingResultNotFoundException e) {
        return ResponseEntity.status(MatchingResultErrorCode.NOT_FOUND.getStatus())
                .body(ErrorResponse.of(MatchingResultErrorCode.NOT_FOUND));
    }
}
