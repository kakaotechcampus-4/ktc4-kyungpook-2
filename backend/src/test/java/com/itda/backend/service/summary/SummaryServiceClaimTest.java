package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.matching.MatchingResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.repository.summary.SummaryRunRequestRepository;

/** 요약할 묶음을 고르는 조건 — 교사가 [요약]을 눌렀고, 같은 기관·날짜에 처리 중인 일지가 없음 (#181). */
@DataJpaTest
@ActiveProfiles("test")
class SummaryServiceClaimTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 17, 0);
    private static final SummaryGroup GROUP = new SummaryGroup(8L, DATE, 3L);

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

    @Autowired
    private SummaryResultRepository summaryResultRepository;

    @Autowired
    private SummaryRunRequestRepository summaryRunRequestRepository;

    private SummaryRunRequests runRequests;
    private Long school;
    private Long center;

    @BeforeEach
    void setUp() {
        runRequests = new SummaryRunRequests(summaryRunRequestRepository);
        school = em.persist(new RawRecord("3", "school.pdf", "kept/school.pdf", "application/pdf", 12,
                RawRecordStatus.PENDING)).getId();
        center = em.persist(new RawRecord("4", "center.pdf", "kept/center.pdf", "application/pdf", 12,
                RawRecordStatus.PENDING)).getId();
    }

    private SummaryService serviceAt(LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(KST).toInstant(), KST);
        return new SummaryService(journalEntryRepository, matchingResultRepository, childRepository,
                organizationRepository, summaryResultRepository, runRequests, clock);
    }

    private List<ClaimedSummaryGroup> claim() {
        return serviceAt(NOW).claimReadyGroups(10);
    }

    private ClaimedSummaryGroup claimedOf(SummaryGroup group, JournalEntry... entries) {
        return new ClaimedSummaryGroup(group, Arrays.stream(entries).map(JournalEntry::getId).toList(), NOW);
    }

    private void requestRun(SummaryGroup group) {
        runRequests.request(group, NOW.minusMinutes(1));
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

    private JournalEntryStatus statusOf(JournalEntry entry) {
        em.flush();
        em.clear();
        return em.find(JournalEntry.class, entry.getId()).getStatus();
    }

    @Test
    void 요약을_누르지_않은_묶음은_요약하지_않는다() {
        // 자동 요약은 없다. 다음 날이 지나도 교사가 누르기 전까지 그대로다.
        JournalEntry entry = validated(school, 8L);

        assertThat(serviceAt(LocalDateTime.of(2026, 10, 20, 14, 0)).claimReadyGroups(10)).isEmpty();
        assertThat(statusOf(entry)).isEqualTo(JournalEntryStatus.VALIDATED);
    }

    @Test
    void 요약을_누른_묶음을_아동_날짜_기관_단위로_집어_가며_요약_중으로_바꾼다() {
        JournalEntry first = validated(school, 8L);
        JournalEntry second = validated(school, 8L);
        validated(school, 9L);
        requestRun(GROUP);

        List<ClaimedSummaryGroup> claimed = claim();

        // 같은 날 다른 아이(9) 묶음은 누르지 않았으니 그대로 둔다.
        assertThat(claimed).containsExactly(claimedOf(GROUP, first, second));
        assertThat(statusOf(first)).isEqualTo(JournalEntryStatus.SUMMARIZING);
        assertThat(statusOf(second)).isEqualTo(JournalEntryStatus.SUMMARIZING);
    }

    @Test
    void 같은_아이_같은_날짜라도_기관이_다르면_다른_묶음이다() {
        JournalEntry atSchool = validated(school, 8L);
        JournalEntry atCenter = validated(center, 8L);
        SummaryGroup centerGroup = new SummaryGroup(8L, DATE, 4L);
        requestRun(GROUP);
        requestRun(centerGroup);

        assertThat(claim()).containsExactly(claimedOf(GROUP, atSchool), claimedOf(centerGroup, atCenter));
    }

    @Test
    void 집을_때는_요청을_처리됨으로_바꾸지_않는다() {
        validated(school, 8L);
        requestRun(GROUP);

        assertThat(claim()).hasSize(1);
        // AI 가 죽어 묶음이 되돌아가도 다음 차례에 다시 요약하도록, 결과를 남길 때 처리 표시를 한다.
        assertThat(runRequests.isRequested(GROUP)).isTrue();
    }

    @Test
    void 요약을_눌러도_같은_기관_같은_날짜에_매칭이나_검증이_끝나지_않은_일지가_있으면_기다린다() {
        JournalEntry entry = validated(school, 8L);
        // 아직 매칭 전이라 누구 일지인지 모른다. 이 아이 일지일 수도 있으니 기관·날짜 단위로 기다린다.
        JournalEntry notMatchedYet = pending(school);
        requestRun(GROUP);

        assertThat(claim()).isEmpty();
        assertThat(statusOf(entry)).isEqualTo(JournalEntryStatus.VALIDATED);
        assertThat(statusOf(notMatchedYet)).isEqualTo(JournalEntryStatus.PENDING);
        // 처리 중인 일지가 끝나면 이어서 요약하도록 요청은 남겨 둔다.
        assertThat(runRequests.isRequested(GROUP)).isTrue();
    }

    @Test
    void 처리가_끝나고_보니_요약할_일지가_없으면_요청을_거둔다() {
        // 업로드 직후 눌러 받아 둔 요청인데, 매칭해 보니 다른 아이 일지였다.
        JournalEntry otherChild = validated(school, 9L);
        requestRun(GROUP);

        assertThat(claim()).isEmpty();
        assertThat(runRequests.isRequested(GROUP)).isFalse();
        assertThat(statusOf(otherChild)).isEqualTo(JournalEntryStatus.VALIDATED);
    }

    @Test
    void 결과를_남기지_못해_요약_중에_멈춘_묶음의_요청은_거두지_않는다() {
        // 결과 저장이 실패해 일지가 요약 중으로 남았다. 재시작하면 요약 대기로 돌아가니 그때 다시 요약해야 한다.
        JournalEntry stuck = validated(school, 8L);
        stuck.startSummarizing();
        em.persistAndFlush(stuck);
        requestRun(GROUP);

        assertThat(claim()).isEmpty();
        assertThat(runRequests.isRequested(GROUP)).isTrue();
    }

    @Test
    void 거둔_요청은_나중에_올라온_일지를_누르지_않았는데_요약하게_하지_않는다() {
        requestRun(GROUP);
        claim();
        JournalEntry later = validated(school, 8L);

        assertThat(claim()).isEmpty();
        assertThat(statusOf(later)).isEqualTo(JournalEntryStatus.VALIDATED);
    }

    @Test
    void 다른_기관의_처리_중인_일지는_기다리지_않는다() {
        validated(school, 8L);
        pending(center);
        requestRun(GROUP);

        assertThat(claim()).hasSize(1);
    }

    @Test
    void 사람_확인이_필요한_일지는_기다리지_않는다() {
        validated(school, 8L);
        JournalEntry inReview = JournalEntry.of(school, DATE, "누구인지 애매한 기록", 2);
        inReview.startMatching();
        inReview.requestMatchReview();
        em.persistAndFlush(inReview);
        requestRun(GROUP);

        assertThat(claim()).hasSize(1);
    }

    @Test
    void 요약_실패와_시스템_오류로_멈춘_일지는_묶음을_막지도_다시_집히지도_않는다() {
        JournalEntry ok = validated(school, 8L);
        JournalEntry summaryFailed = validated(school, 8L);
        summaryFailed.startSummarizing();
        summaryFailed.failSummary();
        em.persistAndFlush(summaryFailed);
        JournalEntry systemError = validated(school, 8L);
        systemError.startSummarizing();
        systemError.failSummaryBySystemError(FailureReason.EVIDENCE_MISMATCH);
        em.persistAndFlush(systemError);
        requestRun(GROUP);

        // 요약 실패는 교사가 [다시 요약]으로(#182), 시스템 오류는 관리자가 고친 뒤 되돌려서 다시 요약한다.
        assertThat(claim()).containsExactly(claimedOf(GROUP, ok));
        assertThat(statusOf(summaryFailed)).isEqualTo(JournalEntryStatus.SUMMARY_FAILED);
        assertThat(statusOf(systemError)).isEqualTo(JournalEntryStatus.SYSTEM_ERROR);
    }

    private SummaryResult summaryOf(int revision) {
        return em.persistAndFlush(SummaryResult.of(8L, DATE, 3L, revision, "블록 놀이를 했다.", "[]", "[]", "[]", "{}"));
    }

    private JournalEntry inSummary(SummaryResult summary) {
        JournalEntry entry = validated(school, 8L);
        entry.startSummarizing();
        entry.completeSummary(summary.getId());
        return em.persistAndFlush(entry);
    }

    private JournalEntry lateEntry() {
        return validated(school, 8L);
    }

    /** 같은 묶음에 새 일지가 들어온 뒤 교사가 다시 [요약]을 눌렀다. */
    private List<ClaimedSummaryGroup> claimAfterRequest() {
        requestRun(GROUP);
        return claim();
    }

    @Test
    void 승인_전_요약에_들어간_일지는_새_일지가_오면_함께_다시_요약한다() {
        JournalEntry summarized = inSummary(summaryOf(1));
        JournalEntry late = lateEntry();

        List<ClaimedSummaryGroup> claimed = claimAfterRequest();

        assertThat(claimed).containsExactly(claimedOf(GROUP, summarized, late));
        assertThat(statusOf(summarized)).isEqualTo(JournalEntryStatus.SUMMARIZING);
    }

    @Test
    void 보류된_요약의_일지는_새_일지와_묶지_않는다() {
        SummaryResult held = summaryOf(1);
        JournalEntry heldEntry = inSummary(held);
        held.hold();
        heldEntry.holdGate1();
        em.flush();
        JournalEntry late = lateEntry();

        List<ClaimedSummaryGroup> claimed = claimAfterRequest();

        // 새 일지만 새 판으로 요약한다. 보류한 판과 그 일지는 교사가 보류함에서 처리할 때까지 그대로 둔다.
        assertThat(claimed).containsExactly(claimedOf(GROUP, late));
        assertThat(statusOf(heldEntry)).isEqualTo(JournalEntryStatus.GATE1_HELD);
    }

    @Test
    void 새_일지는_최신_판의_Gate1_대기_일지와만_묶는다() {
        // 1판을 보류한 사이 2판이 만들어졌고, 그 뒤 1판을 보류 해제했다. 두 판이 모두 Gate 1 대기다.
        SummaryResult reopened = summaryOf(1);
        JournalEntry reopenedEntry = inSummary(reopened);
        JournalEntry latestEntry = inSummary(summaryOf(2));
        JournalEntry late = lateEntry();

        List<ClaimedSummaryGroup> claimed = claimAfterRequest();

        // 1판 일지까지 묶으면 2판에 1판 내용이 섞이고, 1판을 승인하면 같은 일지가 두 번 올라간다.
        assertThat(claimed).containsExactly(claimedOf(GROUP, latestEntry, late));
        assertThat(statusOf(reopenedEntry)).isEqualTo(JournalEntryStatus.GATE1_PENDING);
    }

    @Test
    void 최신_판이_승인됐으면_새_일지만_묶는다() {
        SummaryResult approved = summaryOf(1);
        JournalEntry approvedEntry = inSummary(approved);
        approved.approve();
        approvedEntry.completeGate1();
        em.flush();
        JournalEntry late = lateEntry();

        assertThat(claimAfterRequest()).containsExactly(claimedOf(GROUP, late));
        assertThat(statusOf(approvedEntry)).isEqualTo(JournalEntryStatus.COMPLETED);
    }

    @Test
    void 승인_뒤_같은_묶음에_새_일지가_와도_요약을_누르기_전에는_새_판을_만들지_않는다() {
        SummaryResult approved = summaryOf(1);
        JournalEntry approvedEntry = inSummary(approved);
        approved.approve();
        approvedEntry.completeGate1();
        em.flush();
        JournalEntry late = lateEntry();

        assertThat(claim()).isEmpty();
        assertThat(statusOf(late)).isEqualTo(JournalEntryStatus.VALIDATED);
    }

    @Test
    void 새_일지가_없으면_승인_전_요약을_다시_만들지_않는다() {
        JournalEntry summarized = validated(school, 8L);
        summarized.startSummarizing();
        summarized.completeSummary(50L);
        em.persistAndFlush(summarized);
        requestRun(GROUP);

        assertThat(claim()).isEmpty();
        assertThat(statusOf(summarized)).isEqualTo(JournalEntryStatus.GATE1_PENDING);
    }

    @Test
    void 한_번에_집는_묶음_수를_제한한다() {
        validated(school, 8L);
        validated(school, 9L);
        requestRun(GROUP);
        requestRun(new SummaryGroup(9L, DATE, 3L));

        assertThat(serviceAt(NOW).claimReadyGroups(1)).hasSize(1);
    }

    private JournalEntry summarizingAgain() {
        JournalEntry entry = validated(school, 8L);
        entry.startSummarizing();
        entry.completeSummary(50L);
        entry.startSummarizing();
        return em.persistAndFlush(entry);
    }

    @Test
    void 집어_갔지만_처리하지_않은_묶음은_요약하기_전_상태로_돌려놓는다() {
        JournalEntry fresh = validated(school, 8L);
        fresh.startSummarizing();
        em.persistAndFlush(fresh);
        JournalEntry again = summarizingAgain();

        serviceAt(NOW).release(List.of(claimedOf(GROUP, fresh, again)));

        assertThat(statusOf(fresh)).isEqualTo(JournalEntryStatus.VALIDATED);
        assertThat(statusOf(again)).isEqualTo(JournalEntryStatus.GATE1_PENDING);
    }

    @Test
    void 앱이_꺼져_요약_중에_멈춘_일지를_되돌린다() {
        JournalEntry fresh = validated(school, 8L);
        fresh.startSummarizing();
        em.persistAndFlush(fresh);
        JournalEntry again = summarizingAgain();
        JournalEntry untouched = validated(school, 9L);

        int released = serviceAt(NOW).releaseStuck();

        assertThat(released).isEqualTo(2);
        assertThat(statusOf(fresh)).isEqualTo(JournalEntryStatus.VALIDATED);
        assertThat(statusOf(again)).isEqualTo(JournalEntryStatus.GATE1_PENDING);
        assertThat(statusOf(untouched)).isEqualTo(JournalEntryStatus.VALIDATED);
    }
}
