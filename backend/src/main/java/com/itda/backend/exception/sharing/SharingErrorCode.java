package com.itda.backend.exception.sharing;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

public enum SharingErrorCode implements ErrorCode {

    /** 내게 열리지 않은 인사이트에는 "없음" 으로 답한다 — 있는지조차 알려주지 않는다 (deny-by-default). */
    NOT_FOUND(HttpStatus.NOT_FOUND, "SHARING_NOT_FOUND", "인사이트를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    SharingErrorCode(HttpStatus status, String code, String message) {
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
