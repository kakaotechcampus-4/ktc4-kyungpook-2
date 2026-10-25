package com.itda.backend.exception.insight;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

public enum InsightErrorCode implements ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INSIGHT_INVALID_REQUEST", "처리할 수 없는 요청입니다."),
    /**
     * 근거를 내지 않은 기관에는 "없음" 으로 답한다 — 남의 아이 인사이트가 있는지조차 알려주지 않는다
     * (deny-by-default, CLAUDE.md).
     */
    NOT_FOUND(HttpStatus.NOT_FOUND, "INSIGHT_NOT_FOUND", "인사이트를 찾을 수 없습니다."),
    NOT_PRIMARY_SOURCE(HttpStatus.FORBIDDEN, "INSIGHT_NOT_PRIMARY_SOURCE",
            "이 인사이트의 발송은 다른 기관이 승인합니다."),
    ALREADY_REVIEWED(HttpStatus.CONFLICT, "INSIGHT_ALREADY_REVIEWED", "이미 처리된 인사이트입니다."),
    /** 재료 기관의 동의가 꺼졌다. 인사이트는 여러 기관 claim 을 엮은 결과라 일부만 뺄 수 없어 통째로 막는다. */
    MATERIAL_CONSENT_REVOKED(HttpStatus.CONFLICT, "INSIGHT_MATERIAL_CONSENT_REVOKED",
            "재료가 된 기관의 공유 동의가 철회되어 발송할 수 없습니다."),
    SUPERSEDED(HttpStatus.CONFLICT, "INSIGHT_SUPERSEDED",
            "같은 기간의 인사이트가 새로 만들어졌습니다. 최신 인사이트를 확인해주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    InsightErrorCode(HttpStatus status, String code, String message) {
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
