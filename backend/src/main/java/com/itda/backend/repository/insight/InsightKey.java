package com.itda.backend.repository.insight;

import java.time.LocalDate;

import com.itda.backend.domain.insight.InsightStatus;

/**
 * 인사이트 한 건을 판정하는 데 쓰는 칸만 — 누구 · 어느 주 · 몇 회차 · 상태 · 근거 기관. 본문 · LLM 원문 · 근거 JSON 은
 * 읽지 않는다. 대시보드가 10초마다 묻는 대기 수와 주간 단계가 쓴다.
 */
public record InsightKey(Long id, Long childId, LocalDate periodFrom, LocalDate periodTo, Integer revision,
        InsightStatus status, String sourceInstitutionIds) {
}
