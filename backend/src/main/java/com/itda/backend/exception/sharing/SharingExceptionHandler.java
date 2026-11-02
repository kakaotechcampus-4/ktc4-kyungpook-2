package com.itda.backend.exception.sharing;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.itda.backend.global.exception.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

// 공유 전용 예외만 처리한다. GlobalExceptionHandler 의 catch-all 이 먼저 잡으면 404/400 대신 500 이 나간다.
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SharingExceptionHandler {

    @ExceptionHandler(SharingException.class)
    public ResponseEntity<ErrorResponse> handle(SharingException e) {
        log.warn("공유 처리 실패: {}", e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getStatus()).body(ErrorResponse.of(e.getErrorCode()));
    }
}
