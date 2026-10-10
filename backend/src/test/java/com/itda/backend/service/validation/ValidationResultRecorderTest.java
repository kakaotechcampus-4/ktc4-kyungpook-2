package com.itda.backend.service.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;
import com.itda.backend.domain.ValidationResult;
import com.itda.backend.domain.ValidationVerdict;
import com.itda.backend.dto.request.ValidationAgentRequest;
import com.itda.backend.dto.response.ValidationAgentResponse;
import com.itda.backend.repository.ValidationResultRepository;

@DataJpaTest
@ActiveProfiles("test")
@Import(ValidationResultRecorder.class)
class ValidationResultRecorderTest {

    @Autowired
    private ValidationResultRecorder recorder;

    @Autowired
    private ValidationResultRepository validationResultRepository;

    @Autowired
    private TestEntityManager em;

    private JournalEntry validatingEntry() {
        JournalEntry entry = JournalEntry.of(3L, LocalDate.of(2026, 9, 1), "임유진이 블록을 높이 쌓았다.", 1);
        entry.startMatching();
        entry.confirmMatch(8L);
        entry.startValidating();
        return em.persistAndFlush(entry);
    }

    private ValidationTarget target(JournalEntry entry) {
        return new ValidationTarget(new ValidationAgentRequest(entry.getId(), entry.getContent(), 8L, "임유진"), 7L);
    }

    private ValidationAgentReply reply(JournalEntry entry, String verdict, String issueTypes, String evidence)
            throws Exception {
        String json = """
                {"journal_entry_id": %d, "verdict": "%s", "issue_types": %s, "evidence": %s}
                """.formatted(entry.getId(), verdict, issueTypes, evidence);
        return new ValidationAgentReply(new ObjectMapper().readValue(json, ValidationAgentResponse.class), json);
    }

    private JournalEntry reload(JournalEntry entry) {
        em.flush();
        em.clear();
        return em.find(JournalEntry.class, entry.getId());
    }

    private List<ValidationResult> resultsOf(Long journalEntryId) {
        return validationResultRepository.findAll().stream()
                .filter(r -> r.getJournalEntryId().equals(journalEntryId))
                .toList();
    }

    @Test
    void 통과하면_결과를_남기고_요약_대기로_넘긴다() throws Exception {
        JournalEntry entry = validatingEntry();
        ValidationAgentReply reply = reply(entry, "PASS", "[]", "[]");

        recorder.record(target(entry), reply);

        assertThat(reload(entry).getStatus()).isEqualTo(JournalEntryStatus.VALIDATED);
        ValidationResult result = resultsOf(entry.getId()).get(0);
        assertThat(result.getVerdict()).isEqualTo(ValidationVerdict.PASS);
        assertThat(result.getMatchingResultId()).isEqualTo(7L);
        assertThat(result.getChildId()).isEqualTo(8L);
        assertThat(result.getIssueTypes()).isEqualTo("[]");
        assertThat(result.getEvidence()).isEqualTo("[]");
        assertThat(result.getRawResponse()).isEqualTo(reply.rawJson());
    }

    @Test
    void 교사_확인이_필요해도_요약_대기로_넘긴다() throws Exception {
        // REVIEW 도 요약으로 넘기고 Gate 1 에서 문제 문장을 표시한다 (AI 팀 확인, 10/1).
        JournalEntry entry = validatingEntry();

        recorder.record(target(entry), reply(entry, "REVIEW", "[\"추측성표현\"]", "[{\"start\": 0, \"end\": 5}]"));

        assertThat(reload(entry).getStatus()).isEqualTo(JournalEntryStatus.VALIDATED);
        ValidationResult result = resultsOf(entry.getId()).get(0);
        assertThat(result.getVerdict()).isEqualTo(ValidationVerdict.REVIEW);
        assertThat(result.getIssueTypes()).isEqualTo("[\"추측성표현\"]");
        assertThat(result.getEvidence()).isEqualTo("[{\"start\":0,\"end\":5}]");
    }

    @Test
    void 막히면_결과를_남기고_요약으로_넘기지_않는다() throws Exception {
        JournalEntry entry = validatingEntry();

        recorder.record(target(entry), reply(entry, "BLOCK", "[\"개인정보표현\"]", "[{\"start\": 23, \"end\": 36}]"));

        assertThat(reload(entry).getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_BLOCKED);
        assertThat(resultsOf(entry.getId())).singleElement()
                .extracting(ValidationResult::getVerdict).isEqualTo(ValidationVerdict.BLOCK);
    }

    @Test
    void 처리_중에_일지가_삭제됐으면_결과를_버린다() throws Exception {
        JournalEntry entry = validatingEntry();
        entry.delete();
        em.persistAndFlush(entry);

        recorder.record(target(entry), reply(entry, "PASS", "[]", "[]"));

        assertThat(resultsOf(entry.getId())).isEmpty();
        assertThat(reload(entry).getStatus()).isEqualTo(JournalEntryStatus.VALIDATING);
    }

    /**
     * 호출이 실패하면 verdict = FAILED 로 행을 남긴다(#122).
     *
     * <p>예전에는 "판정이 없다"는 이유로 행을 안 남겼는데, 그러면 실패가 로그에만 남고
     * 매칭 실패인지 검증 실패인지 일지 상태만으로 구분할 수 없었다. 매칭이 이미
     * MatchingStatus.FAILED 로 행을 남기고 있어 그쪽과 대칭을 맞췄다.
     */
    @Test
    void 호출이_실패하면_FAILED_판정으로_행을_남긴다() {
        JournalEntry entry = validatingEntry();

        recorder.recordFailure(entry.getId());

        assertThat(reload(entry).getStatus()).isEqualTo(JournalEntryStatus.FAILED);
        assertThat(resultsOf(entry.getId()))
                .singleElement()
                .satisfies(result -> assertThat(result.getVerdict()).isEqualTo(ValidationVerdict.FAILED));
    }

    @Test
    void 실패를_남길_때_일지가_삭제됐으면_건드리지_않는다() {
        JournalEntry entry = validatingEntry();
        entry.delete();
        em.persistAndFlush(entry);

        recorder.recordFailure(entry.getId());

        assertThat(reload(entry).getStatus()).isEqualTo(JournalEntryStatus.VALIDATING);
        assertThat(resultsOf(entry.getId())).isEmpty();
    }
}
