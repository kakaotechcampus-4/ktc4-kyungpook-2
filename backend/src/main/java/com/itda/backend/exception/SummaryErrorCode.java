package com.itda.backend.exception;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

public enum SummaryErrorCode implements ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "SUMMARY_INVALID_REQUEST", "요약할 수 없는 요청입니다."),
    CHILD_NOT_FOUND(HttpStatus.NOT_FOUND, "SUMMARY_CHILD_NOT_FOUND", "아동을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    SummaryErrorCode(HttpStatus status, String code, String message) {
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
