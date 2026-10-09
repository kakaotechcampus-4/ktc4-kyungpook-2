package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.MatchingResultRepository;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.service.agent.WorkerProperties;

/** 요약할 묶음을 고르는 조건 — 마감(다음 날 03:00), 디바운스(30분), 같은 기관·날짜에 처리 중인 일지 없음. */
@DataJpaTest
@ActiveProfiles("test")
class SummaryServiceClaimTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    // 마감: 10/9 03:00 KST
    private static final LocalDateTime BEFORE_CUTOFF = LocalDateTime.of(2026, 10, 9, 2, 59);
    private static final LocalDateTime AFTER_CUTOFF = LocalDateTime.of(2026, 10, 9, 3, 0);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private MatchingResultRepository matchingResultRepository;

    @Autowired
    private ChildRepository childRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private SummaryRunRequests runRequests;
    private Long school;
    private Long center;

    @BeforeEach
    void setUp() {
        runRequests = new SummaryRunRequests();
        school = em.persist(new RawRecord("3", "school.pdf", "kept/school.pdf", "application/pdf", 12,
                RawRecordStatus.PENDING)).getId();
        center = em.persist(new RawRecord("4", "center.pdf", "kept/center.pdf", "application/pdf", 12,
                RawRecordStatus.PENDING)).getId();
    }

    private SummaryService serviceAt(LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(KST).toInstant(), KST);
        SummaryProperties properties = new SummaryProperties(new WorkerProperties(true, 5000, 10),
                LocalTime.of(3, 0), Duration.ofMinutes(30), KST);
        return new SummaryService(journalEntryRepository, matchingResultRepository, childRepository,
                organizationRepository, runRequests, properties, clock);
    }

    private JournalEntry pending(Long rawRecordId) {
        return em.persistAndFlush(JournalEntry.of(rawRecordId, DATE, "블록 놀이를 했다.", 1));
    }

    private JournalEntry validated(Long rawRecordId, Long childId) {
        JournalEntry entry = JournalEntry.of(rawRecordId, DATE, "블록 놀이를 했다.", 1);
        entry.startMatching();
        entry.confirmMatch(childId);
        entry.startValidating();
        entry.passValidation();
        return em.persistAndFlush(entry);
    }

    private JournalEntry uploadedAt(JournalEntry entry, LocalDateTime createdAt) {
        // createdAt 은 @PrePersist 가 채우므로 디바운스를 재려면 직접 바꾼다.
        em.getEntityManager().createQuery("update JournalEntry e set e.createdAt = :t where e.id = :id")
                .setParameter("t", createdAt.atZone(KST).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime())
                .setParameter("id", entry.getId())
                .executeUpdate();
        return entry;
    }

    private JournalEntryStatus statusOf(JournalEntry entry) {
        em.flush();
        em.clear();
        return em.find(JournalEntry.class, entry.getId()).getStatus();
    }

    @Test
    void 마감_전에는_요약하지_않는다() {
        JournalEntry entry = uploadedAt(validated(school, 8L), DATE.atTime(16, 0));

        List<ClaimedSummaryGroup> claimed = serviceAt(BEFORE_CUTOFF).claimReadyGroups(10);

        assertThat(claimed).isEmpty();
        assertThat(statusOf(entry)).isEqualTo(JournalEntryStatus.VALIDATED);
    }

    @Test
    void 마감이_지나면_아동_날짜_기관_묶음으로_집어_가며_요약_중으로_바꾼다() {
        JournalEntry first = uploadedAt(validated(school, 8L), DATE.atTime(16, 0));
        JournalEntry second = uploadedAt(validated(school, 8L), DATE.atTime(16, 5));

        List<ClaimedSummaryGroup> claimed = serviceAt(AFTER_CUTOFF).claimReadyGroups(10);

        assertThat(claimed).containsExactly(new ClaimedSummaryGroup(
                new SummaryGroup(8L, DATE, 3L), List.of(first.getId(), second.getId())));
        assertThat(statusOf(first)).isEqualTo(JournalEntryStatus.SUMMARIZING);
        assertThat(statusOf(second)).isEqualTo(JournalEntryStatus.SUMMARIZING);
    }

    @Test
    void 같은_아이_같은_날짜라도_기관이_다르면_다른_묶음이다() {
        JournalEntry atSchool = uploadedAt(validated(school, 8L), DATE.atTime(16, 0));
        JournalEntry atCenter = uploadedAt(validated(center, 8L), DATE.atTime(19, 0));

        List<ClaimedSummaryGroup> claimed = serviceAt(AFTER_CUTOFF).claimReadyGroups(10);

        assertThat(claimed).containsExactly(
                new ClaimedSummaryGroup(new SummaryGroup(8L, DATE, 3L), List.of(atSchool.getId())),
                new ClaimedSummaryGroup(new SummaryGroup(8L, DATE, 4L), List.of(atCenter.getId())));
    }

    @Test
    void 마감이_지났어도_마지막_일지가_들어온_지_30분이_안_됐으면_기다린다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 14, 0);
        uploadedAt(validated(school, 8L), now.minusMinutes(40));
        JournalEntry late = uploadedAt(validated(school, 8L), now.minusMinutes(10));

        assertThat(serviceAt(now).claimReadyGroups(10)).isEmpty();
        assertThat(statusOf(late)).isEqualTo(JournalEntryStatus.VALIDATED);

        assertThat(serviceAt(now.plusMinutes(20)).claimReadyGroups(10)).hasSize(1);
    }

    @Test
    void 같은_기관_같은_날짜에_매칭이나_검증이_끝나지_않은_일지가_있으면_기다린다() {
        JournalEntry entry = uploadedAt(validated(school, 8L), DATE.atTime(16, 0));
        // 아직 매칭 전이라 누구 일지인지 모른다. 이 아이 일지일 수도 있으니 기관·날짜 단위로 기다린다.
        JournalEntry notMatchedYet = uploadedAt(pending(school), DATE.atTime(16, 1));

        assertThat(serviceAt(AFTER_CUTOFF).claimReadyGroups(10)).isEmpty();
        assertThat(statusOf(entry)).isEqualTo(JournalEntryStatus.VALIDATED);
        assertThat(statusOf(notMatchedYet)).isEqualTo(JournalEntryStatus.PENDING);
    }

    @Test
    void 다른_기관의_처리_중인_일지는_기다리지_않는다() {
        uploadedAt(validated(school, 8L), DATE.atTime(16, 0));
        uploadedAt(pending(center), DATE.atTime(19, 0));

        assertThat(serviceAt(AFTER_CUTOFF).claimReadyGroups(10)).hasSize(1);
    }

    @Test
    void 사람_확인이_필요한_일지는_기다리지_않는다() {
        uploadedAt(validated(school, 8L), DATE.atTime(16, 0));
        JournalEntry inReview = JournalEntry.of(school, DATE, "누구인지 애매한 기록", 2);
        inReview.startMatching();
        inReview.requestMatchReview();
        uploadedAt(em.persistAndFlush(inReview), DATE.atTime(16, 1));

        assertThat(serviceAt(AFTER_CUTOFF).claimReadyGroups(10)).hasSize(1);
    }

    @Test
    void 지금_요약을_누르면_마감과_디바운스를_건너뛴다() {
        JournalEntry entry = uploadedAt(validated(school, 8L), DATE.atTime(16, 0));
        runRequests.request(new SummaryGroup(8L, DATE, 3L));

        List<ClaimedSummaryGroup> claimed = serviceAt(DATE.atTime(16, 1)).claimReadyGroups(10);

        assertThat(claimed).hasSize(1);
        assertThat(statusOf(entry)).isEqualTo(JournalEntryStatus.SUMMARIZING);
        assertThat(runRequests.contains(new SummaryGroup(8L, DATE, 3L))).isFalse();
    }

    @Test
    void 지금_요약을_눌러도_처리_중인_일지가_있으면_기다린다() {
        uploadedAt(validated(school, 8L), DATE.atTime(16, 0));
        uploadedAt(pending(school), DATE.atTime(16, 1));
        runRequests.request(new SummaryGroup(8L, DATE, 3L));

        assertThat(serviceAt(DATE.atTime(16, 2)).claimReadyGroups(10)).isEmpty();
        // 처리 중인 일지가 끝나면 이어서 요약하도록 요청은 남겨 둔다.
        assertThat(runRequests.contains(new SummaryGroup(8L, DATE, 3L))).isTrue();
    }

    @Test
    void 승인_전_요약에_들어간_일지는_새_일지가_오면_함께_다시_요약한다() {
        JournalEntry summarized = validated(school, 8L);
        summarized.startSummarizing();
        summarized.completeSummary(50L);
        uploadedAt(em.persistAndFlush(summarized), DATE.atTime(16, 0));
        JournalEntry late = uploadedAt(validated(school, 8L), LocalDateTime.of(2026, 10, 10, 13, 0));

        List<ClaimedSummaryGroup> claimed = serviceAt(LocalDateTime.of(2026, 10, 10, 14, 0)).claimReadyGroups(10);

        assertThat(claimed).containsExactly(new ClaimedSummaryGroup(
                new SummaryGroup(8L, DATE, 3L), List.of(summarized.getId(), late.getId())));
        assertThat(statusOf(summarized)).isEqualTo(JournalEntryStatus.SUMMARIZING);
    }

    @Test
    void 새_일지가_없으면_승인_전_요약을_다시_만들지_않는다() {
        JournalEntry summarized = validated(school, 8L);
        summarized.startSummarizing();
        summarized.completeSummary(50L);
        uploadedAt(em.persistAndFlush(summarized), DATE.atTime(16, 0));

        assertThat(serviceAt(AFTER_CUTOFF).claimReadyGroups(10)).isEmpty();
        assertThat(statusOf(summarized)).isEqualTo(JournalEntryStatus.GATE1_PENDING);
    }

    @Test
    void 한_번에_집는_묶음_수를_제한한다() {
        uploadedAt(validated(school, 8L), DATE.atTime(16, 0));
        uploadedAt(validated(school, 9L), DATE.atTime(16, 0));

        assertThat(serviceAt(AFTER_CUTOFF).claimReadyGroups(1)).hasSize(1);
    }
}
