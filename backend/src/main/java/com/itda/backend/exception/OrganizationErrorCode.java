package com.itda.backend.exception;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

/**
 * docs/api/api-conventions.md — 기관 도메인 오류 코드.
 */
public enum OrganizationErrorCode implements ErrorCode {

    /** 기관 1곳당 계정 1개. 이미 가입된 사업자등록번호로 다시 가입하려 했다. */
    DUPLICATE_BUSINESS_NUMBER(HttpStatus.CONFLICT, "DUPLICATE_BUSINESS_NUMBER", "이미 등록된 사업자등록번호입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    OrganizationErrorCode(HttpStatus status, String code, String message) {
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
