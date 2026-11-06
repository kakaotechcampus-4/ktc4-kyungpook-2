package com.itda.backend.dto.response.summary;

import java.time.LocalDate;
import java.util.List;

/**
 * 검토 화면의 요약 실패 묶음 하나 (#369). 아동 × 날짜 × 기관 묶음에서 요약하지 못한 일지를 모은다. 교사는
 * [다시 요약] 하거나 [요약 없이 마무리] 한다.
 *
 * @param entries 요약하지 못한 일지와 원문. id 순
 * @param reason  실패 원인 코드. 지금은 {@code NO_GROUNDED_CLAIMS}(근거가 남은 문장이 없음) 하나다
 */
public record FailedSummaryGroupResponse(
        Long childId,
        String childName,
        LocalDate entryDate,
        List<Entry> entries,
        String reason) {

    public record Entry(Long journalEntryId, String content) {
    }
}
