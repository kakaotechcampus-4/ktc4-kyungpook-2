package com.itda.backend.domain;

/** 검증 에이전트 판정. AI 계약(AI/validation/schemas.py ValidationOutput.verdict)과 1:1 이고 대문자 그대로 쓴다. */
public enum ValidationVerdict {
    /** 문제 없음 — 요약으로 넘긴다. */
    PASS,
    /** 교사 확인 필요 — 요약으로 넘기고 Gate 1 에서 문제 문장을 표시한다. */
    REVIEW,
    /** 그대로 두면 위험 — 요약으로 넘기지 않는다. */
    BLOCK,
    /**
     * AI 호출 자체가 실패했다. AI 계약에는 없는 BE 전용 값으로, {@code MatchingStatus.FAILED} 와 같은 역할이다(#122).
     *
     * <p>이 값이 없던 때는 검증 호출이 실패하면 {@code journal_entry.status} 만 FAILED 로 바뀌고
     * {@code validation_result} 에는 아무 행도 남지 않았다. 그래서 (1) 실패 사실이 로그에만 있고,
     * (2) 매칭 실패인지 검증 실패인지 일지 상태만으로는 구분할 수 없었다(PR #108 버그의 원인).
     */
    FAILED
}
