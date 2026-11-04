package com.itda.backend.exception.checklist;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

/**
 * docs/api/api-spec.md §4.7 · §5.6 — 준비물·확인사항 오류 코드.
 */
public enum ChecklistErrorCode implements ErrorCode {

    /**
     * 없는 요청, 다른 기관 요청, 내린 요청, 아이가 보낸 기관의 명부에서 빠진 요청. 이유를 나누지 않는다 — 나누면 다른 기관
     * 요청이 있는지, 아이가 어느 기관 명부에 있는지를 id 를 바꿔 가며 알아낼 수 있다.
     */
    CHECKLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "CHECKLIST_NOT_FOUND", "준비물·확인사항을 찾을 수 없습니다."),

    /** 받을 아이 중 우리 기관 명부에 없는 아이가 있다. 어느 아이인지는 알리지 않는다 — 다른 기관 아이가 있는지 드러난다. */
    INVALID_CHECKLIST_TARGET(HttpStatus.BAD_REQUEST, "INVALID_CHECKLIST_TARGET", "명부에 없는 아이가 있습니다."),

    /** 이어 보기 기준(before)이 우리 기관 요청이 아니다. 응답 코드는 공통 INVALID_REQUEST 와 같다. */
    INVALID_CHECKLIST_CURSOR(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "잘못된 요청입니다."),

    /** 보호자 목록의 status 가 needs_check 가 아니다. 응답 코드는 공통 INVALID_REQUEST 와 같다. */
    INVALID_CHECKLIST_STATUS(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "잘못된 요청입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ChecklistErrorCode(HttpStatus status, String code, String message) {
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
