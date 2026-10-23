package com.itda.backend.service.validation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.validation.ValidationAgentResponse;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.validation.ValidationResultRepository;

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
        // FAILED 는 BE 가 호출 실패를 남기려고 둔 값이라 AI 가 보낼 수 없다. 판정이 없는 응답과 함께 계약 위반으로 보고,
        // 무엇을 보냈는지 봐야 고칠 수 있으니 응답 원문을 FAILED 행에 남긴다.
        if (response.verdict() == null || response.verdict() == ValidationVerdict.FAILED) {
            log.warn("validation verdict missing or not in contract, recorded as system error journalEntryId={}",
                    journalEntryId);
            validationResultRepository.save(ValidationResult.of(journalEntryId, target.matchingResultId(),
                    target.request().subjectChildId(), ValidationVerdict.FAILED, null, null, reply.rawJson()));
            entry.failValidation(FailureReason.RESPONSE_UNREADABLE);
            return;
        }

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
            case FAILED -> throw new IllegalStateException("FAILED verdict is handled above");
        }
    }

    /**
     * 호출이 실패했다. 판정 대신 {@code verdict = FAILED} 로 행을 남기고(#122), 일지는 시스템 오류로 멈추며 원인을
     * 남긴다(#180).
     *
     * <p>행을 남기는 이유: 예전에는 "판정이 없다"는 이유로 행을 남기지 않았는데, 그러면 실패 사실이 로그에만 남았다.
     * 매칭이 이미 {@code MatchingStatus.FAILED} 로 행을 남기고 있어 그쪽과 대칭을 맞춘다. 원인은 일지 한 곳에만 둔다.
     */
    @Transactional
    public void recordFailure(Long journalEntryId, FailureReason reason) {
        JournalEntry entry = journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId).orElse(null);
        if (entry == null) {
            log.warn("journal entry deleted during validation, failure not recorded journalEntryId={}", journalEntryId);
            return;
        }
        validationResultRepository.save(ValidationResult.of(
                journalEntryId, null, entry.getChildId(), ValidationVerdict.FAILED, null, null, null));
        entry.failValidation(reason);
    }

    // AI 가 보낸 JSON 조각을 그대로 문자열로 남긴다 — 화면이 "start"/"end" 같은 원래 키로 읽는다.
    private static String toJson(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }
}
