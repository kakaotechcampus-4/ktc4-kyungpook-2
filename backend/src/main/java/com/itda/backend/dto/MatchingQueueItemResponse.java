package com.itda.backend.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;

import lombok.extern.slf4j.Slf4j;

// ponytail: JournalEntry/Child/RawRecord 엔티티가 아직 없어서, api-spec.md O-22/
// frontend/app/lib/types.ts(MatchingItem)가 요구하는 record(파일명/타입/미리보기)와
// candidates[].{name,group,birthDate}는 아직 못 채운다 — 해당 도메인이 생기면 조인할 것.
// 팀원 리뷰 반영: candidates/evidence는 DB엔 TEXT로 저장돼있지만, FE 계약(MatchingItem)이
// 실제 배열을 기대해서 여기서 JSON으로 파싱해 내려준다(문자열째로 내려주면 FE가 못 씀).
// id/matchedChildId는 frontend/app/lib/types.ts(MatchingItem.id, candidates[].childId)가
// 문자열 id를 기대해서 String으로 내보낸다 — DB 컬럼 자체는 여전히 BIGINT.
@Slf4j
public record MatchingQueueItemResponse(
        String id,
        Long journalEntryId,
        String matchedChildId,
        MatchingStatus status,
        BigDecimal confidence,
        JsonNode candidates,
        JsonNode evidence) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static MatchingQueueItemResponse from(MatchingResult matchingResult) {
        return new MatchingQueueItemResponse(
                String.valueOf(matchingResult.getId()),
                matchingResult.getJournalEntryId(),
                matchingResult.getMatchedChildId() == null
                        ? null
                        : String.valueOf(matchingResult.getMatchedChildId()),
                matchingResult.getStatus(),
                matchingResult.getConfidence(),
                parseJson(matchingResult.getCandidates(), matchingResult.getId()),
                parseJson(matchingResult.getEvidence(), matchingResult.getId()));
    }

    private static JsonNode parseJson(String raw, Long matchingResultId) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            // AI가 쓴 값이 깨진 것 — 조용히 숨기지 않고 로그로 남긴다. 큐 조회 자체는 계속되게
            // null로만 내려준다(한 건 깨졌다고 전체 큐 조회가 500 나면 더 큰 문제가 된다).
            log.warn("failed to parse matching_result json id={}", matchingResultId, e);
            return null;
        }
    }
}
