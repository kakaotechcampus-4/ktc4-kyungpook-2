package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
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
import com.itda.backend.domain.SummaryResult;
import com.itda.backend.domain.SummaryStatus;
import com.itda.backend.dto.request.SummaryAgentRequest;
import com.itda.backend.dto.response.SummaryAgentResponse;
import com.itda.backend.repository.SummaryResultRepository;

@DataJpaTest
@ActiveProfiles("test")
@Import({SummaryResultRecorder.class, SummaryEvidenceChecker.class})
class SummaryResultRecorderTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    private static final SummaryGroup GROUP = new SummaryGroup(8L, DATE, 3L);

    @Autowired
    private SummaryResultRecorder recorder;

    @Autowired
    private SummaryResultRepository summaryResultRepository;

    @Autowired
    private TestEntityManager em;

    private JournalEntry summarizing(String content) {
        JournalEntry entry = JournalEntry.of(5L, DATE, content, 1);
        entry.startMatching();
        entry.confirmMatch(8L);
        entry.startValidating();
        entry.passValidation();
        entry.startSummarizing();
        return em.persistAndFlush(entry);
    }

    /** 승인 전 요약에 이미 들어가 있다가 새 일지와 함께 다시 요약 중인 일지. */
    private JournalEntry resummarizing(SummaryResult existing) {
        JournalEntry entry = JournalEntry.of(5L, DATE, "이미 요약된 일지", 1);
        entry.startMatching();
        entry.confirmMatch(8L);
        entry.startValidating();
        entry.passValidation();
        entry.startSummarizing();
        entry.completeSummary(existing.getId());
        entry.startSummarizing();
        return em.persistAndFlush(entry);
    }

    private ClaimedSummaryGroup claimed(JournalEntry... entries) {
        return new ClaimedSummaryGroup(GROUP, Arrays.stream(entries).map(JournalEntry::getId).toList());
    }

    /** 워커가 AI 에 보낸 요청 — 묶음의 일지 원문. */
    private SummaryAgentRequest sent(JournalEntry... entries) {
        return new SummaryAgentRequest(8L, "김준호", "2026-10-08", 3L, "햇살초등학교",
                Arrays.stream(entries)
                        .map(e -> new SummaryAgentRequest.Source(e.getId(), e.getContent(), "2026-10-08"))
                        .toList(),
                List.of());
    }

    /** 계약대로 만든 AI 응답. 문장 하나가 {@code evidenceFrom} 원문 전체를 인용한다. */
    private SummaryAgentReply reply(String content, JournalEntry evidenceFrom, String covered, String uncovered)
            throws Exception {
        String quote = evidenceFrom.getContent();
        String json = """
                {"child_id": 8, "entry_date": "2026-10-08", "institution_id": 3, "content": "%s",
                 "claims": [{"text": "%s", "evidence": [
                   {"journal_entry_id": %d, "quote": "%s", "span": {"start": 0, "end": %d}}]}],
                 "covered_entry_ids": %s, "uncovered_entry_ids": %s, "llm_called": true}
                """.formatted(content, content, evidenceFrom.getId(), quote,
                quote.codePointCount(0, quote.length()), covered, uncovered);
        return new SummaryAgentReply(new ObjectMapper().readValue(json, SummaryAgentResponse.class), json);
    }

    private JournalEntry reload(JournalEntry entry) {
        em.flush();
        em.clear();
        return em.find(JournalEntry.class, entry.getId());
    }

    @Test
    void 첫_요약은_1번째_판으로_저장하고_묶음의_일지를_모두_Gate1_대기로_보낸다() throws Exception {
        JournalEntry covered = summarizing("블록 놀이에서 친구에게 양보함");
        JournalEntry uncovered = summarizing("특이사항 없음");
        SummaryAgentReply reply = reply("블록 놀이에서 친구에게 양보했다.", covered,
                "[" + covered.getId() + "]", "[" + uncovered.getId() + "]");

        recorder.record(claimed(covered, uncovered), sent(covered, uncovered), reply);

        List<SummaryResult> saved = summaryResultRepository.findAll();
        assertThat(saved).hasSize(1);
        SummaryResult summary = saved.get(0);
        assertThat(summary.getChildId()).isEqualTo(8L);
        assertThat(summary.getEntryDate()).isEqualTo(DATE);
        assertThat(summary.getInstitutionId()).isEqualTo(3L);
        assertThat(summary.getRevision()).isEqualTo(1);
        assertThat(summary.getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
        assertThat(summary.getContent()).isEqualTo("블록 놀이에서 친구에게 양보했다.");
        assertThat(summary.getCoveredEntryIds()).isEqualTo("[" + covered.getId() + "]");
        assertThat(summary.getUncoveredEntryIds()).isEqualTo("[" + uncovered.getId() + "]");
        assertThat(summary.getRawResponse()).isEqualTo(reply.rawJson());
        // 요약에 반영되지 않은 일지도 같은 요약을 가리킨다 — 빠졌다는 사실을 Gate 1 에서 볼 수 있게.
        for (JournalEntry entry : List.of(covered, uncovered)) {
            JournalEntry found = reload(entry);
            assertThat(found.getStatus()).isEqualTo(JournalEntryStatus.GATE1_PENDING);
            assertThat(found.getSummaryId()).isEqualTo(summary.getId());
        }
    }

    @Test
    void 승인_전_요약이_있으면_같은_판을_덮어쓴다() throws Exception {
        SummaryResult existing = summaryResultRepository.saveAndFlush(
                SummaryResult.of(8L, DATE, 3L, 1, "이전 요약", "[]", "[]", "[]", "{}"));
        JournalEntry old = resummarizing(existing);
        JournalEntry late = summarizing("늦게 올라온 일지");

        recorder.record(claimed(old, late), sent(old, late), reply("다시 만든 요약", late, "[]", "[]"));

        List<SummaryResult> saved = summaryResultRepository.findAll();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getId()).isEqualTo(existing.getId());
        assertThat(saved.get(0).getRevision()).isEqualTo(1);
        assertThat(saved.get(0).getContent()).isEqualTo("다시 만든 요약");
        assertThat(reload(old).getSummaryId()).isEqualTo(existing.getId());
        assertThat(reload(late).getSummaryId()).isEqualTo(existing.getId());
        assertThat(reload(late).getStatus()).isEqualTo(JournalEntryStatus.GATE1_PENDING);
    }

    @Test
    void 승인된_판이_있으면_새_판으로_쌓는다() throws Exception {
        SummaryResult approved = summaryResultRepository.saveAndFlush(
                SummaryResult.of(8L, DATE, 3L, 1, "승인된 요약", "[]", "[]", "[]", "{}"));
        // Gate 1 승인은 아직 없어서 상태만 바꿔 둔다.
        em.getEntityManager().createQuery("update SummaryResult s set s.status = :status where s.id = :id")
                .setParameter("status", SummaryStatus.APPROVED)
                .setParameter("id", approved.getId())
                .executeUpdate();
        em.clear();
        JournalEntry late = summarizing("승인 뒤에 올라온 일지");

        recorder.record(claimed(late), sent(late), reply("새 일지만 묶은 요약", late, "[]", "[]"));

        em.flush();
        em.clear();
        SummaryResult newer = summaryResultRepository.findAll().stream()
                .filter(s -> !s.getId().equals(approved.getId())).findFirst().orElseThrow();
        assertThat(newer.getRevision()).isEqualTo(2);
        assertThat(summaryResultRepository.findById(approved.getId()).orElseThrow().getContent())
                .isEqualTo("승인된 요약");
        assertThat(reload(late).getSummaryId()).isEqualTo(newer.getId());
    }

    @Test
    void 본문이_비어_오면_저장하지_않고_실패로_남긴다() throws Exception {
        JournalEntry entry = summarizing("블록 놀이를 했다.");

        recorder.record(claimed(entry), sent(entry), reply("", entry, "[]", "[" + entry.getId() + "]"));

        assertThat(summaryResultRepository.findAll()).isEmpty();
        assertThat(reload(entry).getStatus()).isEqualTo(JournalEntryStatus.FAILED);
    }

    @Test
    void 호출이_실패하면_새_일지는_실패로_이미_요약된_일지는_Gate1_대기로_남긴다() {
        SummaryResult existing = summaryResultRepository.saveAndFlush(
                SummaryResult.of(8L, DATE, 3L, 1, "이전 요약", "[]", "[]", "[]", "{}"));
        JournalEntry old = resummarizing(existing);
        JournalEntry late = summarizing("늦게 올라온 일지");

        recorder.recordFailure(claimed(old, late));

        assertThat(reload(old).getStatus()).isEqualTo(JournalEntryStatus.GATE1_PENDING);
        assertThat(reload(late).getStatus()).isEqualTo(JournalEntryStatus.FAILED);
        assertThat(summaryResultRepository.findById(existing.getId()).orElseThrow().getContent())
                .isEqualTo("이전 요약");
    }

    @Test
    void 요약하는_사이_삭제된_일지는_건드리지_않는다() throws Exception {
        JournalEntry kept = summarizing("블록 놀이를 했다.");
        JournalEntry removed = summarizing("점심을 먹었다.");
        SummaryAgentRequest request = sent(kept, removed);
        removed.delete();
        em.persistAndFlush(removed);

        recorder.record(claimed(kept, removed), request, reply("블록 놀이를 했다.", kept, "[]", "[]"));

        assertThat(reload(kept).getStatus()).isEqualTo(JournalEntryStatus.GATE1_PENDING);
        assertThat(reload(removed).getStatus()).isEqualTo(JournalEntryStatus.SUMMARIZING);
        assertThat(reload(removed).getSummaryId()).isNull();
    }

    @Test
    void 근거가_원문과_맞지_않으면_저장하지_않고_실패로_남긴다() throws Exception {
        JournalEntry entry = summarizing("블록 놀이를 했다.");
        JournalEntry other = summarizing("점심을 먹었다.");
        SummaryAgentReply reply = reply("블록 놀이를 했다.", other, "[]", "[]");
        // 다른 일지 원문을 이 일지 id 로 인용한 응답 — AI 쪽 버그를 흉내 낸다.
        String broken = reply.rawJson().replace("\"journal_entry_id\": " + other.getId(),
                "\"journal_entry_id\": " + entry.getId());
        SummaryAgentReply brokenReply = new SummaryAgentReply(
                new ObjectMapper().readValue(broken, SummaryAgentResponse.class), broken);

        recorder.record(claimed(entry, other), sent(entry, other), brokenReply);

        assertThat(summaryResultRepository.findAll()).isEmpty();
        assertThat(reload(entry).getStatus()).isEqualTo(JournalEntryStatus.FAILED);
        assertThat(reload(other).getStatus()).isEqualTo(JournalEntryStatus.FAILED);
    }

    @Test
    void 요약하는_사이_삭제된_일지를_근거로_들면_요약_전체를_실패로_남긴다() throws Exception {
        JournalEntry kept = summarizing("블록 놀이를 했다.");
        JournalEntry removed = summarizing("점심을 먹었다.");
        SummaryAgentRequest request = sent(kept, removed);
        removed.delete();
        em.persistAndFlush(removed);

        recorder.record(claimed(kept, removed), request, reply("점심을 먹었다.", removed, "[]", "[]"));

        assertThat(summaryResultRepository.findAll()).isEmpty();
        assertThat(reload(kept).getStatus()).isEqualTo(JournalEntryStatus.FAILED);
    }
}
