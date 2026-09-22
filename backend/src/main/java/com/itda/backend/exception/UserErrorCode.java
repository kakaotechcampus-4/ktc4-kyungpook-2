package com.itda.backend.exception;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

/**
 * docs/api/api-conventions.md — 사용자 도메인 오류 코드.
 */
public enum UserErrorCode implements ErrorCode {

    /**
     * 토큰은 유효한데 그 사용자가 DB 에 없다. 404 가 아니라 401 이다 —
     * 자격 증명이 더 이상 누구도 가리키지 못하는 상태이므로 해야 할 일은 "다시 로그인" 이고,
     * 프론트도 401 을 "역할 없음 → 로그인 화면" 으로 해석한다.
     *
     * <p>규약 표의 USER_NOT_FOUND(404) 와 상태가 달라 혼동되지 않도록 코드명을 따로 둔다.
     */
    SESSION_USER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "SESSION_USER_NOT_FOUND", "인증 정보를 확인할 수 없습니다."),

    /** 로그인은 됐지만 기관 소속이 아니다. 기관 전용 API 에서 막힌다. */
    ORGANIZATION_NOT_ASSIGNED(HttpStatus.FORBIDDEN, "ORGANIZATION_NOT_ASSIGNED", "기관 소속 사용자만 이용할 수 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    UserErrorCode(HttpStatus status, String code, String message) {
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
