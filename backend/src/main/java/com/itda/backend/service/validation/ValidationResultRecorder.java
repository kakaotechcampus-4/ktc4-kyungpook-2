package com.itda.backend.service.validation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.ValidationResult;
import com.itda.backend.domain.ValidationVerdict;
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

    /**
     * 호출이 실패했다. 판정 대신 {@code verdict = FAILED} 로 행을 남긴다(#122).
     *
     * <p>예전에는 "판정이 없다"는 이유로 행을 남기지 않았는데, 그러면 실패 사실이 로그에만 남고
     * 매칭 실패인지 검증 실패인지도 일지 상태만으로는 구분할 수 없었다. 매칭이 이미
     * {@code MatchingStatus.FAILED} 로 행을 남기고 있어 그쪽과 대칭을 맞춘다.
     *
     * <p>실패 사유·재시도 횟수를 담을 컬럼은 아직 없다 — 재시도·타임아웃 규약이 정해진 뒤에
     * 그 계약에 맞춰 추가한다. 지금은 "언제 어느 일지가 검증에서 실패했는지"까지만 남긴다.
     */
    @Transactional
    public void recordFailure(Long journalEntryId) {
        JournalEntry entry = journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId).orElse(null);
        if (entry == null) {
            log.warn("journal entry deleted during validation, failure not recorded journalEntryId={}", journalEntryId);
            return;
        }
        validationResultRepository.save(ValidationResult.of(
                journalEntryId, null, entry.getChildId(), ValidationVerdict.FAILED, null, null, null));
        entry.failValidation();
    }

    // AI 가 보낸 JSON 조각을 그대로 문자열로 남긴다 — 화면이 "start"/"end" 같은 원래 키로 읽는다.
    private static String toJson(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }
}
