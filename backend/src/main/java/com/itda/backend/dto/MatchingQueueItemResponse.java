package com.itda.backend.dto;

import java.math.BigDecimal;

import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;

// ponytail: JournalEntry/Child/RawRecord 엔티티가 아직 없어서, api-spec.md O-22가 요구하는
// record(파일명/타입/미리보기)·candidates(후보 아동 목록) 필드는 아직 못 채운다.
// 해당 도메인이 생기면 조인해서 채워 넣을 것.
public record MatchingQueueItemResponse(
        Long id,
        Long journalEntryId,
        Long matchedChildId,
        MatchingStatus status,
        BigDecimal confidence,
        String reason) {

    public static MatchingQueueItemResponse from(MatchingResult matchingResult) {
        return new MatchingQueueItemResponse(
                matchingResult.getId(),
                matchingResult.getJournalEntryId(),
                matchingResult.getMatchedChildId(),
                matchingResult.getStatus(),
                matchingResult.getConfidence(),
                matchingResult.getReason());
    }
}
