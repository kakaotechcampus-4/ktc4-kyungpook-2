package com.itda.backend.exception;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

/**
 * docs/api/api-conventions.md — RawRecord 도메인 오류 코드.
 */
public enum RawRecordErrorCode implements ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "RAW_RECORD_INVALID_REQUEST", "요청이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "RAW_RECORD_NOT_FOUND", "원본 기록을 찾을 수 없습니다."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "RAW_RECORD_FILE_TOO_LARGE", "파일 크기가 허용 범위를 초과했습니다."),
    STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "RAW_RECORD_STORAGE_FAILED", "원본 기록 저장에 실패했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    RawRecordErrorCode(HttpStatus status, String code, String message) {
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
