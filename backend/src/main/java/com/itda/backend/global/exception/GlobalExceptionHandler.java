package com.itda.backend.global.exception;

import com.itda.backend.exception.AuthException;
import com.itda.backend.exception.UserException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 공통 예외 처리기. catch-all 을 들고 있으므로 <b>반드시 가장 나중에</b> 물어봐야 한다.
 *
 * <p>Spring 은 @RestControllerAdvice 를 순서대로 훑다가 처음 매칭되는 것 하나로 끝낸다.
 * 이 클래스가 먼저 불리면 도메인별 처리기가 영영 실행되지 않고 전부 500 이 된다.
 * 순서를 명시하지 않으면 빈 등록 순서(사실상 패키지 이름 순)가 결정해버려서,
 * 패키지를 옮기는 것만으로 조용히 깨진다.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
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

    @ExceptionHandler(UserException.class)
    public ResponseEntity<ErrorResponse> handleUserException(UserException e) {
        log.warn("사용자 처리 실패: {}", e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(ErrorResponse.of(e.getErrorCode()));
    }

    /**
     * 허용하지 않는 메서드로 부른 경우. 원래는 Spring 의 DefaultHandlerExceptionResolver 가
     * 405 로 바꿔주지만, 아래 {@code Exception} catch-all 이 먼저 매칭되어 500 을 만들고 있었다
     * (HandlerExceptionResolverComposite 는 ExceptionHandlerExceptionResolver 를 먼저 묻는다).
     * 구체 타입 핸들러를 두면 Spring 이 더 구체적인 쪽을 고르므로 405 가 나간다.
     *
     * <p>배포 서버에서 {@code GET /api/v1/auth/logout} 이 500 을 내던 버그다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(CommonErrorCode.METHOD_NOT_ALLOWED.getStatus());
        // 405 응답에는 허용 메서드를 알려주는 Allow 헤더가 따라가야 한다.
        if (e.getSupportedHttpMethods() != null) {
            response.allow(e.getSupportedHttpMethods().toArray(HttpMethod[]::new));
        }
        return response.body(ErrorResponse.of(CommonErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("예상하지 못한 서버 오류", e);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }
}
