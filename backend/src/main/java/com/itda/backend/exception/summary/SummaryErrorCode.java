package com.itda.backend.exception.summary;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

public enum SummaryErrorCode implements ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "SUMMARY_INVALID_REQUEST", "요약할 수 없는 요청입니다."),
    CHILD_NOT_FOUND(HttpStatus.NOT_FOUND, "SUMMARY_CHILD_NOT_FOUND", "아동을 찾을 수 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "SUMMARY_NOT_FOUND", "요약을 찾을 수 없습니다."),
    ALREADY_REVIEWED(HttpStatus.CONFLICT, "SUMMARY_ALREADY_REVIEWED", "이미 처리된 요약입니다."),
    REGENERATING(HttpStatus.CONFLICT, "SUMMARY_REGENERATING",
            "새 일지를 반영해 요약을 다시 만드는 중입니다. 잠시 후 다시 시도해주세요."),
    NOT_HELD(HttpStatus.CONFLICT, "SUMMARY_NOT_HELD", "보류된 요약이 아닙니다."),
    HELD(HttpStatus.CONFLICT, "SUMMARY_HELD", "보류된 요약입니다. 보류를 해제한 뒤 검토해주세요."),
    CHANGED(HttpStatus.CONFLICT, "SUMMARY_CHANGED", "요약이 새 기록으로 바뀌었습니다. 다시 확인해주세요.");

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
