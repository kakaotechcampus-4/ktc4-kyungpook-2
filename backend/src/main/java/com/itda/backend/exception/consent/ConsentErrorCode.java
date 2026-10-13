package com.itda.backend.exception.consent;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

/**
 * docs/api/api-spec.md §5.1 G-02 · §5.5 G-41 — 보호자 동의 오류 코드.
 */
public enum ConsentErrorCode implements ErrorCode {

    /**
     * 삭제된 아이, 아이가 명부에 없는 기관, (미리보기에서) 이미 동의한 기관. 이유를 나누지 않는다 —
     * 나누면 연결된 아이가 어느 기관에 다니는지를 기관 id 를 바꿔 가며 알아낼 수 있다.
     */
    CONSENT_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "CONSENT_TARGET_NOT_FOUND", "동의할 기관을 찾을 수 없습니다."),

    /** 보호자가 자기와 연결되지 않은 아이에 동의하려 했다. api-spec §5 규칙대로 공통 FORBIDDEN 코드로 답한다. */
    CHILD_NOT_LINKED(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다."),

    /**
     * 동의할 항목이 없거나 비어 있는 항목이 섞였다. 요청 검증이 먼저 막으므로 서비스의 방어 검사용이다.
     * 엔티티까지 내려가면 {@code IllegalArgumentException} 이 500 이 되므로 그 전에 400 으로 끊는다.
     * 응답 코드는 공통 {@code INVALID_REQUEST} 와 같다.
     */
    INVALID_CONSENT_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "잘못된 요청입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ConsentErrorCode(HttpStatus status, String code, String message) {
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
