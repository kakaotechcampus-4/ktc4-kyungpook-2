package com.itda.backend.dto.response.dashboard;

/**
 * 오늘(서울 0시부터) 우리 기관 기록에 AI 가 한 일 (#370, api-spec O-81). 선생님이 처리한 횟수는 넣지 않는다.
 *
 * @param split      나눠 만든 일지 수
 * @param matched    AI 가 그대로 확정한 매칭 수 (선생님이 확인 큐에서 고른 것은 뺀다)
 * @param validated  검증 결과 수 — 통과(PASS·REVIEW)와 막힘(BLOCK). AI 호출 실패는 뺀다
 * @param summarized 만든 요약 수 (근사 — 다시 요약한 검토 대기 판 포함)
 * @param insights   만든 인사이트 중 우리 기관이 근거를 낸 것의 수
 */
public record AiActivityResponse(long split, long matched, long validated, long summarized, long insights) {
}
