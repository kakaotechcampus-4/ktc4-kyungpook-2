package com.itda.backend.service.record;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.global.config.ClockConfig;

@DataJpaTest
@ActiveProfiles("test")
@Import({RemovedRecordPurgeService.class, ClockConfig.class})
class RemovedRecordPurgeServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    @Autowired
    private RemovedRecordPurgeService service;

    @Autowired
    private TestEntityManager em;

    @Test
    void 반려한_지_30일이_지난_일지와_그_매칭_검증_결과_요약을_지운다() {
        Long raw = raw("old.txt");
        Long summary = summary(SummaryStatus.REMOVED);
        Long entry = entry(raw, summary, daysAgo(31));
        Long matching = em.persistAndFlush(MatchingResult.failed(entry)).getId();
        Long validation = em.persistAndFlush(
                ValidationResult.of(entry, matching, null, ValidationVerdict.PASS, null, null, null)).getId();
        Long approval = em.persistAndFlush(Approval.gate1(summary, 1L, ApprovalDecision.REMOVED)).getId();

        RemovedRecordPurgeService.Purged purged = service.purgeBatch();
        em.clear();

        assertThat(purged.entries()).isEqualTo(1);
        assertThat(em.find(JournalEntry.class, entry)).isNull();
        assertThat(em.find(MatchingResult.class, matching)).isNull();
        assertThat(em.find(ValidationResult.class, validation)).isNull();
        assertThat(em.find(SummaryResult.class, summary)).isNull();
        assertThat(em.find(Approval.class, approval)).isNotNull();
    }

    @Test
    void 파일의_일지가_모두_지워지면_원본도_지우고_저장소_키를_돌려준다() {
        Long raw = raw("old.txt");
        entry(raw, summary(SummaryStatus.REMOVED), daysAgo(31));

        RemovedRecordPurgeService.Purged purged = service.purgeBatch();
        em.clear();

        assertThat(em.find(RawRecord.class, raw)).isNull();
        assertThat(purged.storedKeys()).containsExactly("stored-old.txt");
    }

    @Test
    void 같은_파일에_남은_일지가_있으면_원본은_남긴다() {
        Long raw = raw("mixed.txt");
        Long removed = entry(raw, summary(SummaryStatus.REMOVED), daysAgo(31));
        Long alive = em.persistAndFlush(JournalEntry.of(raw, DAY, "살아 있는 일지", 2)).getId();

        RemovedRecordPurgeService.Purged purged = service.purgeBatch();
        em.clear();

        assertThat(em.find(JournalEntry.class, removed)).isNull();
        assertThat(em.find(JournalEntry.class, alive)).isNotNull();
        assertThat(em.find(RawRecord.class, raw)).isNotNull();
        assertThat(purged.storedKeys()).isEmpty();
    }

    @Test
    void 반려한_지_30일이_안_됐으면_건드리지_않는다() {
        Long raw = raw("recent.txt");
        Long summary = summary(SummaryStatus.REMOVED);
        Long entry = entry(raw, summary, daysAgo(29));

        assertThat(service.purgeBatch().entries()).isZero();
        em.clear();

        assertThat(em.find(JournalEntry.class, entry)).isNotNull();
        assertThat(em.find(SummaryResult.class, summary)).isNotNull();
        assertThat(em.find(RawRecord.class, raw)).isNotNull();
    }

    @Test
    void 반려_요약에_아직_남은_일지가_있으면_요약은_남긴다() {
        Long raw = raw("split.txt");
        Long summary = summary(SummaryStatus.REMOVED);
        Long old = entry(raw, summary, daysAgo(31));
        Long recent = entry(raw, summary, daysAgo(10));

        service.purgeBatch();
        em.clear();

        assertThat(em.find(JournalEntry.class, old)).isNull();
        assertThat(em.find(JournalEntry.class, recent)).isNotNull();
        assertThat(em.find(SummaryResult.class, summary)).isNotNull();
    }

    @Test
    void 요약이_반려가_아니면_삭제_표시된_일지라도_지우지_않는다() {
        Long raw = raw("other.txt");
        Long entry = entry(raw, summary(SummaryStatus.APPROVED), daysAgo(31));

        assertThat(service.purgeBatch().entries()).isZero();
        em.clear();

        assertThat(em.find(JournalEntry.class, entry)).isNotNull();
    }

    private Long raw(String name) {
        return em.persistAndFlush(new RawRecord("7", name, "stored-" + name, "text/plain", 10L,
                RawRecordStatus.PENDING)).getId();
    }

    private Long summary(SummaryStatus status) {
        SummaryResult summary = SummaryResult.of(1L, DAY, 7L, 1, "요약", "[]", "[]", "[]", "{}");
        ReflectionTestUtils.setField(summary, "status", status);
        return em.persistAndFlush(summary).getId();
    }

    private Long entry(Long rawRecordId, Long summaryId, LocalDateTime deletedAt) {
        JournalEntry entry = JournalEntry.of(rawRecordId, DAY, "반려된 일지", 1);
        ReflectionTestUtils.setField(entry, "summaryId", summaryId);
        ReflectionTestUtils.setField(entry, "deletedAt", deletedAt);
        return em.persistAndFlush(entry).getId();
    }

    private static LocalDateTime daysAgo(int days) {
        return LocalDateTime.now().minusDays(days);
    }
}
