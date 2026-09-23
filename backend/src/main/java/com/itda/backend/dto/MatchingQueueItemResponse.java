package com.itda.backend.dto;

import java.math.BigDecimal;

import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;

// ponytail: JournalEntry/Child/RawRecord 엔티티가 아직 없어서, api-spec.md O-22/
// frontend/app/lib/types.ts(MatchingItem)가 요구하는 record(파일명/타입/미리보기)와
// candidates[].{name,group,birthDate}는 아직 못 채운다 — 해당 도메인이 생기면 조인할 것.
// candidates/evidence는 일단 AI가 넣어준 원문 JSON을 그대로 내려준다(파싱 안 함,
// JSON 라이브러리 미도입) — evidence는 {start,end}[] 형태라 FE EvidenceSpan[]과 이미 맞다.
// id/matchedChildId는 frontend/app/lib/types.ts(MatchingItem.id, candidates[].childId)가
// 문자열 id를 기대해서 String으로 내보낸다 — DB 컬럼 자체는 여전히 BIGINT.
public record MatchingQueueItemResponse(
        String id,
        Long journalEntryId,
        String matchedChildId,
        MatchingStatus status,
        BigDecimal confidence,
        String candidates,
        String evidence) {

    public static MatchingQueueItemResponse from(MatchingResult matchingResult) {
        return new MatchingQueueItemResponse(
                String.valueOf(matchingResult.getId()),
                matchingResult.getJournalEntryId(),
                matchingResult.getMatchedChildId() == null
                        ? null
                        : String.valueOf(matchingResult.getMatchedChildId()),
                matchingResult.getStatus(),
                matchingResult.getConfidence(),
                matchingResult.getCandidates(),
                matchingResult.getEvidence());
    }
}
