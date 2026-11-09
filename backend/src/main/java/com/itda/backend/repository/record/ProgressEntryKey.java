package com.itda.backend.repository.record;

import java.time.LocalDate;

/**
 * 대시보드 AI 진행 단계(#453)가 AI 의 일지 번호를 진행 현황 줄로 바꿀 때 읽는 값. 본문은 읽지 않는다.
 *
 * @param childId       아직 아이를 못 찾았으면 {@code null}
 * @param institutionId 원본 파일을 올린 기관 ({@code raw_record.institution_id}, 문자열)
 */
public record ProgressEntryKey(Long id, Long childId, LocalDate entryDate, String institutionId) {
}
