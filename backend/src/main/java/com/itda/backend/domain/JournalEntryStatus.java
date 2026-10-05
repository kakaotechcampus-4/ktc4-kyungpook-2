package com.itda.backend.domain;

public enum JournalEntryStatus {
    PENDING,
    MATCHING,
    /** 매칭으로 아동이 확정돼 검증을 기다린다. DB 스키마 §6.2 초안에 없던 값을 BE가 추가했다. */
    MATCHED,
    MATCH_REVIEW,
    /** 선생님이 확인 필요 큐에서 제외했다("이 기관 아동 아님"). 여기서 끝나고 다음 단계로 가지 않는다. BE가 추가했다. */
    EXCLUDED,
    CONSENT_BLOCKED,
    VALIDATING,
    SUMMARIZING,
    GATE1_PENDING,
    COMPLETED,
    FAILED
}
