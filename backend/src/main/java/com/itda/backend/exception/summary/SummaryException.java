package com.itda.backend.exception.summary;

import lombok.Getter;

/**
 * 오류 코드를 담은 요약 예외. 응답 메시지는 오류 코드의 고정 문구다 — 예외 메시지(요청 값, 요약 id)는 로그에만 남는다.
 * 409 는 요약이 지금 상태에서 그 결정을 받을 수 없을 때다 — 이미 처리됐거나, 다시 요약하는 중이거나, 보류된 요약이 아니다.
 */
@Getter
public class SummaryException extends RuntimeException {

    private final SummaryErrorCode errorCode;

    public SummaryException(SummaryErrorCode errorCode, Long summaryId) {
        this(errorCode, "summaryId=" + summaryId);
    }

    public SummaryException(SummaryErrorCode errorCode, String detail) {
        super(errorCode.getCode() + " " + detail);
        this.errorCode = errorCode;
    }
}
