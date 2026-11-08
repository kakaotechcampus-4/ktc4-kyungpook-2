package com.itda.backend.exception.validation;

import org.springframework.http.HttpStatus;

import com.itda.backend.global.exception.ErrorCode;

public enum ValidationResultErrorCode implements ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "VALIDATION_RESULT_INVALID_REQUEST", "요청이 올바르지 않습니다."),
    /*
     * 수정 요청 큐 처리(O-25, #357)의 거절 이유. 화면이 이유마다 다른 안내를 한다 — 하나로 묶으면 "동의가 꺼졌으니 보류하라"를
     * 고칠 게 없는 경우에도 보여 줘 멀쩡한 기록을 보류하게 된다.
     */
    /** 보던 판정이 그 일지의 최신 판정이 아니다. 이미 고쳐 다시 검사했는데 또 막혀 새 판정이 생겼다. */
    OUTDATED(HttpStatus.BAD_REQUEST, "VALIDATION_RESULT_OUTDATED", "더 최근 판정이 있습니다. 목록을 새로 불러와 주세요."),
    /** 고친 본문이 지금 본문과 같다(앞뒤 공백만 다른 것 포함). */
    CONTENT_UNCHANGED(HttpStatus.BAD_REQUEST, "VALIDATION_RESULT_CONTENT_UNCHANGED", "본문이 고쳐지지 않았습니다."),
    /** 아이가 삭제됐거나 우리 명부에서 빠졌거나 우리 기관 동의가 꺼져 다시 검증으로 보낼 수 없다. 보류만 할 수 있다. */
    CHILD_NOT_SHARED(HttpStatus.BAD_REQUEST, "VALIDATION_RESULT_CHILD_NOT_SHARED", "공유가 끝난 아이의 기록은 다시 검증할 수 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "VALIDATION_RESULT_NOT_FOUND", "검증 결과를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ValidationResultErrorCode(HttpStatus status, String code, String message) {
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
