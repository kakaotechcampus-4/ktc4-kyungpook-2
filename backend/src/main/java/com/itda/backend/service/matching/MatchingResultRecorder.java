package com.itda.backend.service.matching;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.MatchingResult;
import com.itda.backend.dto.response.MatchingAgentResponse;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.MatchingResultRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 판정 결과를 matching_result 와 journal_entry 에 쓰는 곳은 여기 하나다. matching_result 구조가
 * 바뀌면 이 클래스만 고친다. (사람이 확인 필요 큐에서 처리하는 경로는 MatchingResultService 가 따로 쓴다.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingResultRecorder {

    private final JournalEntryRepository journalEntryRepository;
    private final MatchingResultRepository matchingResultRepository;

    @Transactional
    public void record(Long journalEntryId, MatchingAgentReply reply) {
        JournalEntry entry = journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId).orElse(null);
        if (entry == null) {
            log.warn("journal entry deleted during matching, result dropped journalEntryId={}", journalEntryId);
            return;
        }
        MatchingAgentResponse response = reply.response();

        matchingResultRepository.save(new MatchingResult(
                journalEntryId,
                response.matchedChildId(),
                toDecimal(response.confidence()),
                response.status(),
                response.multiReason(),
                response.hintMismatch(),
                toJson(response.candidates()),
                toJson(response.evidence()),
                toJson(response.mentionedChildIds()),
                reply.rawJson(),
                null));

        // matched_child_id 는 AI 가 이번 요청의 명부 안에서만 고른다 (AI/matching/nodes.py 가 명부로 검증).
        switch (response.status()) {
            case AUTO -> entry.confirmMatch(response.matchedChildId());
            case REVIEW, MULTI, UNMATCHED -> entry.requestMatchReview();
            default -> throw new IllegalStateException("unexpected matching status from agent: " + response.status());
        }
    }

    @Transactional
    public void recordFailure(Long journalEntryId) {
        JournalEntry entry = journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId).orElse(null);
        if (entry == null) {
            log.warn("journal entry deleted during matching, failure not recorded journalEntryId={}", journalEntryId);
            return;
        }
        matchingResultRepository.save(MatchingResult.failed(journalEntryId));
        entry.failMatching();
    }

    private static BigDecimal toDecimal(Double confidence) {
        return confidence == null ? null : BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP);
    }

    // AI 가 보낸 JSON 조각을 그대로 문자열로 남긴다 — 확인 필요 큐가 "child_id" 같은 원래 키로 읽는다.
    private static String toJson(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }
}
