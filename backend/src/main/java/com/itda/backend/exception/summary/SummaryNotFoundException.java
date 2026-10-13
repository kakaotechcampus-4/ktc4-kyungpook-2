package com.itda.backend.exception.summary;

/** 요약이 없거나 다른 기관 것이다. 다른 기관 요약도 "권한 없음"이 아니라 "없음"으로 응답한다. */
public class SummaryNotFoundException extends RuntimeException {

    public SummaryNotFoundException(Long summaryId) {
        super("summary not found in institution: " + summaryId);
    }
}
