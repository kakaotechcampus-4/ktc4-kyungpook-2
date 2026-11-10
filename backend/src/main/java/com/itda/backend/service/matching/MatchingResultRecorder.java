package com.itda.backend.service.matching;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.dto.response.matching.MatchingAgentResponse;
import com.itda.backend.repository.matching.MatchingResultRepository;
import com.itda.backend.repository.record.JournalEntryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    @Transactional
    public void recordSystemError(Long journalEntryId, FailureReason reason) {
        JournalEntry entry = journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId).orElse(null);
        if (entry == null) {
            log.warn("journal entry deleted during matching, system error not recorded journalEntryId={}", journalEntryId);
            return;
        }
        entry.failMatchingBySystemError(reason);
    }

    private static BigDecimal toDecimal(Double confidence) {
        return confidence == null ? null : BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP);
    }

    private static String toJson(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }
}
