package com.itda.backend.exception.child;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

/**
 * docs/api/api-conventions.md — 아동 도메인 오류 코드.
 */
public enum ChildErrorCode implements ErrorCode {

    /** 같은 기관에서 이미 쓰고 있는 관리번호로 아이를 등록하려 했다. 다른 기관은 같은 번호를 쓸 수 있다. */
    DUPLICATE_EXTERNAL_ID(HttpStatus.CONFLICT, "DUPLICATE_EXTERNAL_ID", "이미 사용 중인 관리번호입니다."),

    /**
     * 없거나 삭제됐거나 우리 기관 명부에 없는 아이. 다른 기관 아이도 같은 404 다 —
     * 403 으로 나누면 그 id 의 아이가 어딘가에 있다는 사실이 새어 나간다.
     */
    CHILD_NOT_FOUND(HttpStatus.NOT_FOUND, "CHILD_NOT_FOUND", "아동을 찾을 수 없습니다.");

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
