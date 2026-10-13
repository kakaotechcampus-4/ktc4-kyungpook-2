package com.itda.backend.exception.summary;

import lombok.Getter;

/** 요약이 지금 상태에서 그 결정을 받을 수 없다 — 이미 처리됐거나, 다시 요약하는 중이거나, 보류된 요약이 아니다. */
@Getter
public class SummaryConflictException extends RuntimeException {

    private final SummaryErrorCode errorCode;

    public SummaryConflictException(SummaryErrorCode errorCode, Long summaryId) {
        super(errorCode.getCode() + " summaryId=" + summaryId);
        this.errorCode = errorCode;
    }
}
