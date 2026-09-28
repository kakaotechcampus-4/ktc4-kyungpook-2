package com.itda.backend.domain;

public enum JournalEntryStatus {
    PENDING,
    MATCHING,
    MATCH_REVIEW,
    CONSENT_BLOCKED,
    VALIDATING,
    SUMMARIZING,
    GATE1_PENDING,
    COMPLETED,
    FAILED
}
