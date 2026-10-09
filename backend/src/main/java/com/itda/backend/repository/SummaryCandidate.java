package com.itda.backend.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 요약 묶음을 고를 때 쓰는 일지 한 건의 요약 정보. 기관은 일지에 없어 raw_record 를 거쳐 가져온다.
 *
 * @param institutionId raw_record.institution_id 그대로(VARCHAR). 숫자로 바꾸는 것은 서비스가 한다
 */
public record SummaryCandidate(Long journalEntryId, Long childId, LocalDate entryDate, String institutionId,
        LocalDateTime createdAt) {
}
