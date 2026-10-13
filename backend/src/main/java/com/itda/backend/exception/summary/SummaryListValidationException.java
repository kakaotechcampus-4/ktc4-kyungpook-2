package com.itda.backend.exception.summary;

/** Gate 1 목록 조회 조건이 잘못됐다 — 지원하지 않는 gate1Status. */
public class SummaryListValidationException extends RuntimeException {

    public SummaryListValidationException(String message) {
        super(message);
    }
}
