package com.itda.backend.domain;

public enum JournalEntryStatus {
    PENDING,
    MATCHING,
    /** 매칭으로 아동이 확정돼 검증을 기다린다. DB 스키마 §6.2 초안에 없던 값을 BE가 추가했다. */
    MATCHED,
    MATCH_REVIEW,
    CONSENT_BLOCKED,
    VALIDATING,
    SUMMARIZING,
    GATE1_PENDING,
    COMPLETED,
    FAILED
}
