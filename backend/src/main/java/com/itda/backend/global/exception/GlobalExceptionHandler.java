package com.itda.backend.global.exception;

import com.itda.backend.domain.auth.exception.AuthErrorCode;
import com.itda.backend.domain.auth.exception.AuthException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 카카오가 잘못된 요청(예: 만료·위조된 인가코드)에 4xx로 응답한 경우 — 클라이언트가 고칠 수 있는 오류. */
    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ErrorResponse> handleHttpClientErrorException(HttpClientErrorException e) {
        log.warn("카카오가 잘못된 요청으로 판단해 {} 응답: {}", e.getStatusCode(), e.getMessage());
        return ResponseEntity.status(AuthErrorCode.KAKAO_AUTH_FAILED.getStatus())
                .body(ErrorResponse.of(AuthErrorCode.KAKAO_AUTH_FAILED));
    }

    /** 카카오 서버 자체가 5xx로 응답한 경우 — 외부 서비스 장애이지 클라이언트 잘못이 아니다. */
    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ErrorResponse> handleHttpServerErrorException(HttpServerErrorException e) {
        log.error("카카오 서버 오류 {} 응답: {}", e.getStatusCode(), e.getMessage());
        return ResponseEntity.status(AuthErrorCode.KAKAO_SERVER_ERROR.getStatus())
                .body(ErrorResponse.of(AuthErrorCode.KAKAO_SERVER_ERROR));
    }

    /** 카카오 서버 타임아웃·연결 실패 — 응답 자체가 없는 경우. */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleResourceAccessException(ResourceAccessException e) {
        log.error("카카오 서버 연결 실패·타임아웃: {}", e.getMessage());
        return ResponseEntity.status(AuthErrorCode.KAKAO_UNAVAILABLE.getStatus())
                .body(ErrorResponse.of(AuthErrorCode.KAKAO_UNAVAILABLE));
    }

    /** 위 세 타입 중 어디에도 해당하지 않는 카카오 REST 호출 실패에 대한 안전망 — 상세 원인은 로그에만 남기고 공통 코드로 응답한다. */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResponse> handleRestClientException(RestClientException e) {
        log.error("카카오 REST 호출 중 분류되지 않은 오류: {}", e.getMessage());
        return ResponseEntity.status(AuthErrorCode.KAKAO_AUTH_FAILED.getStatus())
                .body(ErrorResponse.of(AuthErrorCode.KAKAO_AUTH_FAILED));
    }

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
