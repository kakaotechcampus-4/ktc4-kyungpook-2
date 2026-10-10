package com.itda.backend.service.summary;

import java.time.LocalDate;

/**
 * 요약 한 편의 묶음 키 — 아동 × 날짜 × 기관 (DB 스키마 §8.2). 기관을 가로질러 묶지 않는다.
 *
 * @param institutionId organization.id. raw_record.institution_id(VARCHAR)를 숫자로 바꾼 값이다.
 */
public record SummaryGroup(Long childId, LocalDate entryDate, Long institutionId) {
}
