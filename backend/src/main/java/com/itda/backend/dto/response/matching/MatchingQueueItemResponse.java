package com.itda.backend.dto.response.matching;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.matching.MatchingStatus;
import com.itda.backend.domain.matching.MultiReason;
import com.itda.backend.dto.response.record.RecordResponse;

// ponytail: JournalEntry/RawRecord/Child 조인·JSON 파싱이 필요해서(id/matchedChildId도
// frontend/app/lib/types.ts 기대에 맞춰 String으로 변환) MatchingResultService가 값을
// 전부 채워 넣은 뒤 생성한다 — 이 레코드는 순수 데이터만 들고 있다.
// evidence는 {start,end}[] 그대로라 파싱만 하면 FE EvidenceSpan[]과 이미 맞다.
// hintName 은 업로드 때 파일 이름에서 정한 표지 힌트(명부 아이 한 명이 파일 이름에 들어 있을 때만). 없으면 null —
// 아이 확인 화면은 그때 파일 이름을 보인다(#381).
public record MatchingQueueItemResponse(
        String id,
        Long journalEntryId,
        String matchedChildId,
        MatchingStatus status,
        BigDecimal confidence,
        MultiReason multiReason,
        Boolean hintMismatch,
        String hintName,
        RecordResponse record,
        List<CandidateResponse> candidates,
        JsonNode evidence) {
}
