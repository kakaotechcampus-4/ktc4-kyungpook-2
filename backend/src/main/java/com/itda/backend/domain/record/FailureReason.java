package com.itda.backend.domain.record;

/**
 * 시스템 오류로 멈춘 일지가 왜 멈췄는지 (#180). 버그를 고친 뒤 이 값으로 골라 되돌린다. 세부(어느 문장·근거인지)는
 * 로그에만 남긴다 — 아이 기록이라 본문을 DB 에 옮겨 적지 않는다.
 */
public enum FailureReason {

    /** AI 가 요청을 거절했다(4xx, FastAPI 422 포함). BE 가 계약과 다른 요청을 보냈다. */
    REQUEST_REJECTED,

    /** AI 응답을 읽을 수 없다. 본문 없음·JSON 아님·필수 값 빠짐, 요약 본문이 빈 200 도 여기다. */
    RESPONSE_UNREADABLE,

    /** BE 근거 재검사에 걸렸다. 다른 묶음의 응답, 보내지 않은 일지 번호, 원문과 다른 인용 등이다. */
    EVIDENCE_MISMATCH,

    /**
     * 검증 AI 가 다운이라 되돌린 횟수가 상한({@code app.ai.agent-down.max-releases})을 넘었다(#153). 코드 버그가 아니라
     * AI 장애일 수 있어서, AI 가 살아나면 이 원인만 골라 되돌린다.
     */
    AGENT_UNAVAILABLE,

    /** 위 어디에도 맞지 않는 BE 자체 오류다. 매칭된 아이가 없는 일지, DB 오류 등이다. */
    INTERNAL_ERROR
}
