package com.itda.backend.dto.response.matching;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.matching.MatchingStatus;
import com.itda.backend.domain.matching.MultiReason;
import com.itda.backend.dto.response.record.RecordResponse;

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
