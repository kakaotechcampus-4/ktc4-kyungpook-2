package com.itda.backend.exception.child;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

/**
 * docs/api/api-conventions.md — 이미 있는 아이를 다른 기관 명부에 붙이는 연결 요청의 오류 코드.
 */
public enum InstitutionLinkErrorCode implements ErrorCode {

    /** 형식이 틀렸거나 이 코드를 쓰는 기관이 없다. 둘을 나누지 않는다. */
    INSTITUTION_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "INSTITUTION_CODE_NOT_FOUND", "일치하는 기관을 찾지 못했습니다."),

    /** 아이가 이미 그 기관 명부에 있다. 요청할 것도, 수락할 것도 없다. */
    INSTITUTION_ALREADY_LINKED(HttpStatus.CONFLICT, "INSTITUTION_ALREADY_LINKED", "이미 연결된 기관입니다."),

    /**
     * 이 기관에 대기 중인 요청이 없는 아이. 삭제된 아이, 다른 기관에만 요청한 아이, 이미 처리된 요청도 같은 404 다 —
     * 이유를 나누면 그 id 의 아이가 어딘가에 있다는 사실이 새어 나간다.
     */
    LINK_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "LINK_REQUEST_NOT_FOUND", "연결 요청을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    InstitutionLinkErrorCode(HttpStatus status, String code, String message) {
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
