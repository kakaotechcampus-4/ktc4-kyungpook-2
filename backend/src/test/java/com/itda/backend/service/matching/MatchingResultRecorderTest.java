package com.itda.backend.service.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;
import com.itda.backend.domain.MultiReason;
import com.itda.backend.dto.response.MatchingAgentResponse;
import com.itda.backend.repository.MatchingResultRepository;

@DataJpaTest
@ActiveProfiles("test")
@Import(MatchingResultRecorder.class)
class MatchingResultRecorderTest {

    @Autowired
    private MatchingResultRecorder recorder;

    @Autowired
    private MatchingResultRepository matchingResultRepository;

    @Autowired
    private TestEntityManager em;

    private JournalEntry matchingEntry() {
        JournalEntry entry = JournalEntry.of(3L, LocalDate.of(2026, 9, 1), "점심을 잘 먹음", 1);
        entry.startMatching();
        return em.persistAndFlush(entry);
    }

    private MatchingAgentReply reply(String json) throws Exception {
        return new MatchingAgentReply(new ObjectMapper().readValue(json, MatchingAgentResponse.class), json);
    }

    private MatchingResult onlyResultOf(Long journalEntryId) {
        List<MatchingResult> results = matchingResultRepository.findAll().stream()
                .filter(r -> r.getJournalEntryId().equals(journalEntryId))
                .toList();
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    private JournalEntry reload(JournalEntry entry) {
        em.flush();
        em.clear();
        return em.find(JournalEntry.class, entry.getId());
    }

    @Test
    void 자동_확정이면_결과를_남기고_일지에_아동을_확정한다() throws Exception {
        JournalEntry entry = matchingEntry();
        String json = """
                {"journal_entry_id": %d, "status": "auto", "matched_child_id": 8, "confidence": 0.99,
                 "hint_mismatch": false, "evidence": [{"start": 0, "end": 3}], "mentioned_child_ids": [8],
                 "multi_reason": null, "candidates": [], "llm_called": true}
                """.formatted(entry.getId());

        recorder.record(entry.getId(), reply(json));

        MatchingResult result = onlyResultOf(entry.getId());
        assertThat(result.getStatus()).isEqualTo(MatchingStatus.AUTO);
        assertThat(result.getMatchedChildId()).isEqualTo(8L);
        assertThat(result.getConfidence()).isEqualByComparingTo(new BigDecimal("0.9900"));
        assertThat(result.getHintMismatch()).isFalse();
        assertThat(result.getEvidence()).isEqualTo("[{\"start\":0,\"end\":3}]");
        assertThat(result.getMentionedChildIds()).isEqualTo("[8]");
        assertThat(result.getReviewerId()).isNull();
        assertThat(result.getRawResponse()).isEqualTo(json);
        JournalEntry saved = reload(entry);
        assertThat(saved.getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
        assertThat(saved.getChildId()).isEqualTo(8L);
    }

    @Test
    void 후보가_여럿이면_후보를_AI_형식_그대로_남기고_사람_확인으로_보낸다() throws Exception {
        JournalEntry entry = matchingEntry();
        String json = """
                {"journal_entry_id": %d, "status": "multi", "matched_child_id": null, "confidence": 0.62,
                 "hint_mismatch": true, "evidence": [], "mentioned_child_ids": [8, 9],
                 "multi_reason": "co_mention",
                 "candidates": [{"child_id": 8, "confidence": 0.62}, {"child_id": 9, "confidence": 0.6}],
                 "llm_called": true}
                """.formatted(entry.getId());

        recorder.record(entry.getId(), reply(json));

        MatchingResult result = onlyResultOf(entry.getId());
        assertThat(result.getStatus()).isEqualTo(MatchingStatus.MULTI);
        assertThat(result.getMultiReason()).isEqualTo(MultiReason.CO_MENTION);
        assertThat(result.getHintMismatch()).isTrue();
        // 확인 필요 큐(MatchingResultService.buildCandidates)가 "child_id" 키로 읽는다.
        assertThat(result.getCandidates())
                .isEqualTo("[{\"child_id\":8,\"confidence\":0.62},{\"child_id\":9,\"confidence\":0.6}]");
        // 검증 단계가 "본문에 다른 아이 이름이 남았는지" 판단할 때 쓴다.
        assertThat(result.getMentionedChildIds()).isEqualTo("[8,9]");
        JournalEntry saved = reload(entry);
        assertThat(saved.getStatus()).isEqualTo(JournalEntryStatus.MATCH_REVIEW);
        assertThat(saved.getChildId()).isNull();
    }

    @Test
    void 확인이_필요하거나_명부에_없으면_사람_확인으로_보낸다() throws Exception {
        for (String status : List.of("review", "unmatched")) {
            JournalEntry entry = matchingEntry();
            String json = """
                    {"journal_entry_id": %d, "status": "%s", "matched_child_id": %s, "confidence": 0.4,
                     "hint_mismatch": false, "evidence": [], "mentioned_child_ids": [], "multi_reason": null,
                     "candidates": [], "llm_called": false}
                    """.formatted(entry.getId(), status, status.equals("review") ? "8" : "null");

            recorder.record(entry.getId(), reply(json));

            MatchingResult result = onlyResultOf(entry.getId());
            assertThat(result.getStatus()).isEqualTo(MatchingStatus.fromJson(status));
            // 빈 목록은 "이름이 하나도 안 나왔다"는 뜻이라 null 과 구분해서 남긴다.
            assertThat(result.getMentionedChildIds()).isEqualTo("[]");
            JournalEntry saved = reload(entry);
            assertThat(saved.getStatus()).isEqualTo(JournalEntryStatus.MATCH_REVIEW);
            assertThat(saved.getChildId()).isNull();
        }
    }

    @Test
    void 호출이_실패하면_FAILED_결과를_남기고_일지도_실패로_바꾼다() {
        JournalEntry entry = matchingEntry();

        recorder.recordFailure(entry.getId());

        MatchingResult result = onlyResultOf(entry.getId());
        assertThat(result.getStatus()).isEqualTo(MatchingStatus.FAILED);
        assertThat(result.getRawResponse()).isNull();
        assertThat(result.getMentionedChildIds()).isNull();
        assertThat(reload(entry).getStatus()).isEqualTo(JournalEntryStatus.FAILED);
    }

    @Test
    void 처리_중에_일지가_삭제됐으면_아무것도_남기지_않는다() throws Exception {
        JournalEntry entry = matchingEntry();
        entry.delete();
        em.flush();
        String json = """
                {"journal_entry_id": %d, "status": "auto", "matched_child_id": 8, "confidence": 0.99}
                """.formatted(entry.getId());

        recorder.record(entry.getId(), reply(json));
        recorder.recordFailure(entry.getId());

        assertThat(matchingResultRepository.findAll()).isEmpty();
    }
}
