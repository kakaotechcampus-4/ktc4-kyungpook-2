package com.itda.backend.global.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.itda.backend.exception.record.RawRecordErrorCode;
import java.util.stream.Collectors;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Spring MVC 표준 예외(잘못된 경로·메서드·타입·Content-Type, 누락된 파라미터·파트 등)는
 * {@link ResponseEntityExceptionHandler} 가 알맞은 4xx 로 분류하고, 본문만 API 규약의
 * 실패 응답으로 바꾼다.
 *
 * <p>아래 {@code Exception.class} catch-all 만 두면 이 예외들까지 전부 500 이 된다.
 * {@code @ExceptionHandler} 가 Spring 기본 예외 변환보다 먼저 매칭되기 때문이다.
 *
 * <p>기능별 예외는 모두 {@link BusinessException} 을 상속해 처리기 하나로 받는다. 기능별 처리기를 따로 두지 않는다.
 *
 * <p>catch-all 을 들고 있으므로 <b>반드시 가장 나중에</b> 물어봐야 한다. 다른 @RestControllerAdvice 가 생겨도
 * 이 처리기가 먼저 잡아 전부 500 이 되지 않도록 순서를 명시한다.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException e) {
        return ResponseEntity.status(CommonErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(CommonErrorCode.INVALID_REQUEST));
    }

    /**
     * 오류 코드로 응답하는 예외 전부. 응답은 오류 코드의 상태와 고정 문구이고, 예외 메시지(내부 사유)는 로그에만 남긴다.
     * 5xx 는 원인을 찾아야 하므로 스택트레이스까지 남긴다.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        if (errorCode.getStatus().is5xxServerError()) {
            log.error("요청 처리 실패 code={} reason={}", errorCode.getCode(), e.getMessage(), e);
        } else {
            log.warn("요청 처리 실패 code={} reason={}", errorCode.getCode(), e.getMessage());
        }
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("예상하지 못한 서버 오류", e);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }

    /** multipart 크기 상한을 넘었다. 업로드 경로만 multipart 를 받으므로 업로드 오류 코드로 응답한다. */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return ResponseEntity.status(RawRecordErrorCode.FILE_TOO_LARGE.getStatus())
                .body(ErrorResponse.of(RawRecordErrorCode.FILE_TOO_LARGE));
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
        } else if (log.isDebugEnabled()) {
            log.debug("잘못된 요청: status={} exception={}{}", statusCode.value(), ex.getClass().getSimpleName(), safeDetail(ex));
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

    /**
     * 4xx 로그에 남겨도 되는 정보만 꺼낸다. 예외 메시지는 쓰지 않는다 — 검증 실패에는 거부된 값이,
     * 본문 해석 실패·경로 변수 변환 실패·없는 경로에는 입력 일부가 들어 있어 개인정보가 로그에 남는다.
     * 필드 이름과 위반 코드, 원인 예외 종류, JSON 필드 경로처럼 값이 없는 정보만 남긴다.
     *
     * <p>5xx 는 스택트레이스를 그대로 남기므로 원인 예외에 담긴 입력값까지 막아 주지는 않는다.
     */
    private static String safeDetail(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException invalid) {
            String fields = invalid.getBindingResult().getAllErrors().stream()
                    .map(error -> (error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName())
                            + ":" + error.getCode())
                    .collect(Collectors.joining(","));
            return " fields=" + fields;
        }
        if (ex instanceof HttpMessageNotReadableException unreadable && unreadable.getCause() != null) {
            Throwable cause = unreadable.getCause();
            String detail = " cause=" + cause.getClass().getSimpleName();
            if (cause instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
                detail += " path=" + mapping.getPath().stream()
                        .map(reference -> reference.getFieldName() != null
                                ? reference.getFieldName()
                                : "[" + reference.getIndex() + "]")
                        .collect(Collectors.joining("."));
            }
            return detail;
        }
        return "";
    }
}
