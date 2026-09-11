package com.itda.backend.global.exception;

import com.itda.backend.domain.auth.exception.AuthErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 카카오 토큰 교환·사용자 조회 REST 호출 실패 — 상세 원인은 로그에만 남기고 공통 코드로 응답한다. */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResponse> handleRestClientException(RestClientException e) {
        return ResponseEntity.status(AuthErrorCode.KAKAO_AUTH_FAILED.getStatus())
                .body(ErrorResponse.of(AuthErrorCode.KAKAO_AUTH_FAILED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        return ResponseEntity.status(CommonErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }
}
