package com.itda.backend.domain.record;

/**
 * 시스템 오류로 멈춘 일지가 어느 단계에서 멈췄는지 (#180). 고친 뒤 되돌릴 자리가 단계마다 달라서 남긴다 —
 * 매칭은 매칭 대기(PENDING), 검증은 검증 대기(MATCHED), 요약은 요약 대기(VALIDATED)로 돌린다 (DB 스키마 §6.2).
 */
public enum FailureStage {

    /**
     * 매칭 에이전트에 보내기 전에 멈췄다 (#424). 지금은 본문이 너무 길 때({@link FailureReason#CONTENT_TOO_LONG})뿐이다 —
     * 매칭 호출 실패는 시스템 오류가 아니라 확인 필요 큐({@code FAILED})로 간다.
     */
    MATCHING,

    /** 검증 에이전트 호출·결과 저장에서 멈췄다. */
    VALIDATION,

    /** 요약 에이전트 호출·근거 재검사·결과 저장에서 멈췄다. */
    SUMMARY
}
