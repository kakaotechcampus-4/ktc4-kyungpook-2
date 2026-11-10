package com.itda.backend.global.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
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
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException e) {
        return ResponseEntity.status(CommonErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(CommonErrorCode.INVALID_REQUEST));
    }

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
