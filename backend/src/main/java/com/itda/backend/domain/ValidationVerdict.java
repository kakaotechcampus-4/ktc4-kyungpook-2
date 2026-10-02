package com.itda.backend.domain;

/** 검증 에이전트 판정. AI 계약(AI/validation/schemas.py ValidationOutput.verdict)과 1:1 이고 대문자 그대로 쓴다. */
public enum ValidationVerdict {
    /** 문제 없음 — 요약으로 넘긴다. */
    PASS,
    /** 교사 확인 필요 — 요약으로 넘기고 Gate 1 에서 문제 문장을 표시한다. */
    REVIEW,
    /** 그대로 두면 위험 — 요약으로 넘기지 않는다. */
    BLOCK
}
