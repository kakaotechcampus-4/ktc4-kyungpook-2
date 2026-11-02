package com.itda.backend.dto.response.summary;

/**
 * Gate 1 결정 결과. 화면 모양이 정해지기 전의 임시 응답이다 (#183 에서 확정).
 *
 * @param status   결정 뒤 요약 상태 — APPROVED · HOLD · REMOVED · GATE1_PENDING(보류 해제) · CONFIRMED · REVERTED
 * @param decision 남긴 검토 기록의 결정값 — APPROVED · CORRECTED · HOLD · REMOVED · REOPENED · CONFIRMED · REVERTED
 */
public record Gate1DecisionResponse(Long summaryId, String status, String decision) {
}
