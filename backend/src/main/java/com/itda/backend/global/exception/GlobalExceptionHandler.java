package com.itda.backend.global.exception;

import com.itda.backend.exception.AuthException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Spring MVC 표준 예외(잘못된 경로·메서드·타입·Content-Type, 누락된 파라미터·파트 등)는
 * {@link ResponseEntityExceptionHandler} 가 알맞은 4xx 로 분류하고, 본문만 API 규약의
 * 실패 응답으로 바꾼다.
 *
 * <p>아래 {@code Exception.class} catch-all 만 두면 이 예외들까지 전부 500 이 된다.
 * {@code @ExceptionHandler} 가 Spring 기본 예외 변환보다 먼저 매칭되기 때문이다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

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

    /**
     * 상태 코드와 헤더(405 의 {@code Allow}, 415 의 {@code Accept} 등)는 Spring 이 정한 값을
     * 그대로 쓰고, 기본 ProblemDetail 본문만 {@link ErrorResponse} 로 바꾼다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        if (request instanceof ServletWebRequest servletWebRequest
                && servletWebRequest.getResponse() != null
                && servletWebRequest.getResponse().isCommitted()) {
            return null;
        }

        if (statusCode.is5xxServerError()) {
            log.error("요청 처리 중 서버 오류", ex);
        } else {
            log.debug("잘못된 요청: {}", ex.getMessage());
        }

        return ResponseEntity.status(statusCode)
                .headers(headers)
                .body(ErrorResponse.of(toErrorCode(statusCode)));
    }

    private ErrorCode toErrorCode(HttpStatusCode statusCode) {
        if (statusCode.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return CommonErrorCode.NOT_FOUND;
        }
        if (statusCode.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
            return CommonErrorCode.METHOD_NOT_ALLOWED;
        }
        if (statusCode.isSameCodeAs(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) {
            return CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
        }
        return statusCode.is5xxServerError()
                ? CommonErrorCode.INTERNAL_SERVER_ERROR
                : CommonErrorCode.INVALID_REQUEST;
    }
}
