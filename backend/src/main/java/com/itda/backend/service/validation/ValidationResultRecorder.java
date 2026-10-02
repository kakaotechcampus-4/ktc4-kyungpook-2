package com.itda.backend.service.validation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.ValidationResult;
import com.itda.backend.dto.response.ValidationAgentResponse;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.ValidationResultRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** AI 검증 결과를 validation_result 와 journal_entry 에 쓰는 곳은 여기 하나다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationResultRecorder {

    private final JournalEntryRepository journalEntryRepository;
    private final ValidationResultRepository validationResultRepository;

    @Transactional
    public void record(ValidationTarget target, ValidationAgentReply reply) {
        Long journalEntryId = target.request().journalEntryId();
        JournalEntry entry = journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId).orElse(null);
        if (entry == null) {
            log.warn("journal entry deleted during validation, result dropped journalEntryId={}", journalEntryId);
            return;
        }
        ValidationAgentResponse response = reply.response();

        validationResultRepository.save(ValidationResult.of(
                journalEntryId,
                target.matchingResultId(),
                target.request().subjectChildId(),
                response.verdict(),
                toJson(response.issueTypes()),
                toJson(response.evidence()),
                reply.rawJson()));

        switch (response.verdict()) {
            case PASS, REVIEW -> entry.passValidation();
            case BLOCK -> entry.blockValidation();
        }
    }

    /** 호출이 실패했다. 판정이 없어 결과 행은 남기지 않는다 (verdict 가 NOT NULL). */
    @Transactional
    public void recordFailure(Long journalEntryId) {
        JournalEntry entry = journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId).orElse(null);
        if (entry == null) {
            log.warn("journal entry deleted during validation, failure not recorded journalEntryId={}", journalEntryId);
            return;
        }
        entry.failValidation();
    }

    // AI 가 보낸 JSON 조각을 그대로 문자열로 남긴다 — 화면이 "start"/"end" 같은 원래 키로 읽는다.
    private static String toJson(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }
}
