package com.itda.backend.dto.response.summary;

import java.time.LocalDate;
import java.util.List;

/**
 * Gate 1 검토 목록의 요약 한 편. 화면 모양이 정해지기 전의 초안이다 (#183 에서 확정).
 *
 * @param status           요약 상태 — GATE1_PENDING(검토 대기) · HOLD(보류함)
 * @param needsReview      요약 본문에 다른 아이 이름이 남아 공유 전에 봐야 하는지
 * @param reviewReasons    왜 봐야 하는지 — ["다른아동이름"]
 * @param uncoveredEntries 요약에 안 들어간 일지와 원문. 승인하면 이 일지도 함께 끝난다.
 * @param reviewFlags      요약에 들어간 원본 중 검증에서 REVIEW 를 받은 일지와 그 사유
 */
public record Gate1SummaryResponse(
        Long id,
        Long childId,
        String childName,
        LocalDate entryDate,
        Integer revision,
        String status,
        String content,
        boolean needsReview,
        List<String> reviewReasons,
        List<UncoveredEntry> uncoveredEntries,
        List<ReviewFlag> reviewFlags) {

    public record UncoveredEntry(Long journalEntryId, String content) {
    }

    /** 검증 issue_types 원본. 사유 문장 변환은 화면 확정 뒤에 정한다. */
    public record ReviewFlag(Long journalEntryId, List<String> issueTypes) {
    }
}
