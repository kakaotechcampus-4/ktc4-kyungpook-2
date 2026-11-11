package com.itda.backend.domain.record;

public enum JournalEntryStatus {
    PENDING,
    MATCHING,
    MATCHED,
    MATCH_REVIEW,
    EXCLUDED,
    VALIDATING,
    VALIDATED,
    VALIDATION_BLOCKED,
    VALIDATION_HELD,
    SUMMARIZING,
    GATE1_PENDING,
    GATE1_HELD,
    COMPLETED,
    SUMMARY_FAILED,
    SYSTEM_ERROR,
    FAILED
}
