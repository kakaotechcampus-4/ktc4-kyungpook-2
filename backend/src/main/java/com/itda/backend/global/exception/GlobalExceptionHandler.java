package com.itda.backend.global.exception;

import com.itda.backend.exception.AuthException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException e) {
        return ResponseEntity.status(CommonErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(CommonErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException e) {
        return ResponseEntity.status(CommonErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(CommonErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuthException(AuthException e) {
        log.warn("인증 처리 실패: {}", e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(ErrorResponse.of(e.getErrorCode()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("예상하지 못한 서버 오류", e);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }
}
