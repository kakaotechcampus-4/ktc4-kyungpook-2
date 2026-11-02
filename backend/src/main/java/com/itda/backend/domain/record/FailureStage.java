package com.itda.backend.domain.record;

/**
 * 시스템 오류로 멈춘 일지가 어느 단계에서 멈췄는지 (#180). 고친 뒤 되돌릴 자리가 단계마다 달라서 남긴다 —
 * 검증은 검증 대기(MATCHED), 요약은 요약 대기(VALIDATED)로 돌린다 (DB 스키마 §6.2).
 */
public enum FailureStage {

    /** 검증 에이전트 호출·결과 저장에서 멈췄다. */
    VALIDATION,

    /** 요약 에이전트 호출·근거 재검사·결과 저장에서 멈췄다. */
    SUMMARY
}
