package com.itda.backend.exception.record;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

/**
 * docs/api/api-conventions.md — RawRecord 도메인 오류 코드.
 */
public enum RawRecordErrorCode implements ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "RAW_RECORD_INVALID_REQUEST", "요청이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "RAW_RECORD_NOT_FOUND", "원본 기록을 찾을 수 없습니다."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "RAW_RECORD_FILE_TOO_LARGE", "파일 크기가 허용 범위를 초과했습니다."),
    STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "RAW_RECORD_STORAGE_FAILED", "원본 기록 저장에 실패했습니다."),
    /** 파일에서 나온 일지가 너무 많거나 하나가 너무 길다 (#424). 파일은 저장하지 않는다. */
    ENTRY_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "RAW_RECORD_ENTRY_LIMIT_EXCEEDED", "기록이 너무 많거나 긴 파일입니다."),
    /** 지금 처리 중인 업로드가 많다 (#424). 파일은 저장하지 않는다. */
    UPLOAD_BUSY(HttpStatus.TOO_MANY_REQUESTS, "RAW_RECORD_UPLOAD_BUSY", "지금 올리는 파일이 많습니다. 잠시 뒤 다시 올려 주세요."),
    /** 없는 접수이거나 다른 기관의 접수다 (#450). */
    UPLOAD_NOT_FOUND(HttpStatus.NOT_FOUND, "RAW_RECORD_UPLOAD_NOT_FOUND", "업로드 접수를 찾을 수 없습니다."),
    /** 완료를 알렸지만 저장소에 아직 파일이 없다 (#450). 파일을 올린 뒤 다시 완료를 알린다. */
    UPLOAD_NOT_READY(HttpStatus.CONFLICT, "RAW_RECORD_UPLOAD_NOT_READY", "파일이 아직 올라오지 않았습니다.");

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
