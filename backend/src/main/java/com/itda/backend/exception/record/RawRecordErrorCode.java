package com.itda.backend.exception.record;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RawRecordErrorCode implements ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "RAW_RECORD_INVALID_REQUEST", "요청이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "RAW_RECORD_NOT_FOUND", "원본 기록을 찾을 수 없습니다."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "RAW_RECORD_FILE_TOO_LARGE", "파일 크기가 허용 범위를 초과했습니다."),
    STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "RAW_RECORD_STORAGE_FAILED", "원본 기록 저장에 실패했습니다."),
    ENTRY_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "RAW_RECORD_ENTRY_LIMIT_EXCEEDED", "기록이 너무 많거나 긴 파일입니다."),
    UPLOAD_BUSY(HttpStatus.TOO_MANY_REQUESTS, "RAW_RECORD_UPLOAD_BUSY", "지금 올리는 파일이 많습니다. 잠시 뒤 다시 올려 주세요."),
    UPLOAD_NOT_FOUND(HttpStatus.NOT_FOUND, "RAW_RECORD_UPLOAD_NOT_FOUND", "업로드 접수를 찾을 수 없습니다."),
    UPLOAD_NOT_READY(HttpStatus.CONFLICT, "RAW_RECORD_UPLOAD_NOT_READY", "파일이 아직 올라오지 않았습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    @Override
    public Level logLevel() {
        return this == ENTRY_LIMIT_EXCEEDED || this == UPLOAD_BUSY ? Level.INFO : ErrorCode.super.logLevel();
    }
}
