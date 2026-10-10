package com.itda.backend.exception;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

/**
 * docs/api/api-conventions.md — 아동 도메인 오류 코드.
 */
public enum ChildErrorCode implements ErrorCode {

    /** 같은 기관에서 이미 쓰고 있는 관리번호로 아이를 등록하려 했다. 다른 기관은 같은 번호를 쓸 수 있다. */
    DUPLICATE_EXTERNAL_ID(HttpStatus.CONFLICT, "DUPLICATE_EXTERNAL_ID", "이미 사용 중인 관리번호입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ChildErrorCode(HttpStatus status, String code, String message) {
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
