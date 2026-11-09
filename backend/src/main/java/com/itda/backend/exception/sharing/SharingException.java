package com.itda.backend.exception.sharing;

import com.itda.backend.global.exception.BusinessException;

/** 공유 예외. 응답 메시지는 오류 코드의 고정 문구다 — 상세는 로그에만 남는다. */
public class SharingException extends BusinessException {

    public SharingException(SharingErrorCode errorCode, Long insightId) {
        this(errorCode, "insightId=" + insightId);
    }

    public SharingException(SharingErrorCode errorCode, String detail) {
        super(errorCode, errorCode.getCode() + " " + detail);
    }
}
