package com.itda.backend.exception.insight;

import lombok.Getter;

/**
 * 오류 코드를 담은 인사이트 예외. 응답 메시지는 오류 코드의 고정 문구다 — 예외 메시지(요청 값, 인사이트 id)는
 * 로그에만 남는다. {@code SummaryException} 과 같은 모양이다.
 */
@Getter
public class InsightException extends RuntimeException {

    private final InsightErrorCode errorCode;

    public InsightException(InsightErrorCode errorCode, Long insightId) {
        this(errorCode, "insightId=" + insightId);
    }

    public InsightException(InsightErrorCode errorCode, String detail) {
        super(errorCode.getCode() + " " + detail);
        this.errorCode = errorCode;
    }
}
