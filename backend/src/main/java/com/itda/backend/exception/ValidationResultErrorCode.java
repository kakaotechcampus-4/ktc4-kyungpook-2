package com.itda.backend.exception;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

public enum ValidationResultErrorCode implements ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "VALIDATION_RESULT_INVALID_REQUEST", "요청이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "VALIDATION_RESULT_NOT_FOUND", "검증 결과를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ValidationResultErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    @Override
    public HttpStatus getStatus() {
        return status;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
