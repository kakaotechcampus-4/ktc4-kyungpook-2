package com.itda.backend.exception.sharing;

import lombok.Getter;

/** 공유 예외. 응답 메시지는 오류 코드의 고정 문구다 — 상세는 로그에만 남는다. */
@Getter
public class SharingException extends RuntimeException {

    private final SharingErrorCode errorCode;

    public SharingException(SharingErrorCode errorCode, Long insightId) {
        this(errorCode, "insightId=" + insightId);
    }

    public SharingException(SharingErrorCode errorCode, String detail) {
        super(errorCode.getCode() + " " + detail);
        this.errorCode = errorCode;
    }
}
