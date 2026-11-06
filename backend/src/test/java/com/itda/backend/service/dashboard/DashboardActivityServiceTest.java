package com.itda.backend.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.matching.MatchingStatus;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.dashboard.AiActivityResponse;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.service.insight.InsightActivityReader;
import com.itda.backend.service.user.UserService;

/**
 * 오늘 AI가 한 일 (#370). 시각은 서울 기준 오늘 0시부터 센다. 행의 생성 시각은 엔티티가 JVM 기본 시간대로 남기므로
 * 테스트도 서울 시각을 JVM 시간대로 바꿔 넣는다 — 어느 시간대에서 돌려도 경계가 같다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({DashboardActivityService.class, InsightActivityReader.class, DashboardActivityServiceTest.ClockConfig.class})
class DashboardActivityServiceTest {

    private static final Long ORG = 1L;
    private static final Long OTHER = 2L;
    private static final String TEACHER = "99";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 10, 0);
    private static final LocalDateTime TODAY = LocalDateTime.of(2026, 11, 6, 0, 0);
    private static final LocalDateTime YESTERDAY_LAST = LocalDateTime.of(2026, 11, 5, 23, 59, 59);
    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private TestEntityManager em;

    @Autowired
    private DashboardActivityService service;

    @MockitoBean
    private UserService userService;

    private Long ours;
    private Long theirs;

    @BeforeEach
    void setUp() {
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(ORG);
        ours = rawRecord(ORG);
        theirs = rawRecord(OTHER);
    }

    @Test
    void 아무_일도_없으면_모두_0이다() {
        assertThat(service.getActivity(TEACHER)).isEqualTo(new AiActivityResponse(0, 0, 0, 0, 0));
    }

    @Test
    void 오늘_0시부터_우리_기관에서_나눈_일지만_센다() {
        at(entry(ours), TODAY);
        at(entry(ours), NOW);
        at(entry(ours), YESTERDAY_LAST);
        at(entry(theirs), NOW);

        assertThat(service.getActivity(TEACHER).split()).isEqualTo(2);
    }

    @Test
    void 선생님이_확인한_매칭은_빼고_AI가_자동으로_확정한_매칭만_센다() {
        atMatching(matching(entry(ours), null), NOW);
        atMatching(matching(entry(ours), "teacher-1"), NOW);
        atMatching(matching(entry(ours), null), YESTERDAY_LAST);
        atMatching(matching(entry(theirs), null), NOW);
        MatchingResult review = em.persistAndFlush(new MatchingResult(entry(ours).getId(), null, null,
                MatchingStatus.REVIEW, null, null, null, null, null, null, null));
        atMatching(review, NOW);

        assertThat(service.getActivity(TEACHER).matched()).isEqualTo(1);
    }

    @Test
    void 통과와_막힘_검증_결과를_세고_AI_호출_실패는_뺀다() {
        JournalEntry entry = entry(ours);
        atValidation(validation(entry, ValidationVerdict.PASS), NOW);
        atValidation(validation(entry, ValidationVerdict.REVIEW), NOW);
        atValidation(validation(entry, ValidationVerdict.BLOCK), NOW);
        atValidation(validation(entry, ValidationVerdict.FAILED), NOW);
        atValidation(validation(entry, ValidationVerdict.PASS), YESTERDAY_LAST);
        atValidation(validation(entry(theirs), ValidationVerdict.PASS), NOW);

        assertThat(service.getActivity(TEACHER).validated()).isEqualTo(3);
    }

    @Test
    void 오늘_만든_요약과_오늘_다시_만든_검토_대기_요약을_센다() {
        atSummary(summary(ORG, 1, DATE), NOW, NOW);
        // 어제 만든 판을 오늘 새 일지와 함께 다시 요약했다 — 같은 행을 덮어써 생성 시각은 어제다.
        atSummary(summary(ORG, 2, DATE), YESTERDAY_LAST, NOW);
        // 어제 만들고 오늘 승인한 판은 선생님이 한 일이다.
        SummaryResult approved = summary(ORG, 3, DATE);
        approved.approve();
        atSummary(em.persistAndFlush(approved), YESTERDAY_LAST, NOW);
        atSummary(summary(OTHER, 4, DATE), NOW, NOW);

        assertThat(service.getActivity(TEACHER).summarized()).isEqualTo(2);
    }

    @Test
    void 오늘_만든_인사이트_중_우리_기관이_근거를_낸_것만_센다() {
        atInsight(insight("[1,2]"), NOW);
        atInsight(insight("[2]"), NOW);
        atInsight(insight("[1]"), YESTERDAY_LAST);

        assertThat(service.getActivity(TEACHER).insights()).isEqualTo(1);
    }

    private Long rawRecord(Long institutionId) {
        return em.persistAndFlush(new RawRecord(String.valueOf(institutionId), "0821_특이사항.txt", "stored.txt",
                "text/plain", 10L, RawRecordStatus.PENDING)).getId();
    }

    private JournalEntry entry(Long rawRecordId) {
        return em.persistAndFlush(JournalEntry.of(rawRecordId, DATE, "블록을 쌓았다.", 1));
    }

    private MatchingResult matching(JournalEntry entry, String reviewerId) {
        MatchingResult result = new MatchingResult(entry.getId(), 8L, null, MatchingStatus.AUTO, null, null,
                null, null, null, null, null);
        if (reviewerId != null) {
            result.resolveAsAssigned(8L, reviewerId);
        }
        return em.persistAndFlush(result);
    }

    private ValidationResult validation(JournalEntry entry, ValidationVerdict verdict) {
        return em.persistAndFlush(ValidationResult.of(entry.getId(), null, 8L, verdict, "[]", "[]", "{}"));
    }

    private SummaryResult summary(Long institutionId, long childId, LocalDate date) {
        return em.persistAndFlush(SummaryResult.of(childId, date, institutionId, 1, "요약 본문", "[]", "[]", "[]", "{}"));
    }

    private InsightResult insight(String sourceInstitutionIds) {
        return em.persistAndFlush(InsightResult.of(8L, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1), 1,
                "상황", "반응", "본문", "[]", "[]", "[]", sourceInstitutionIds, false, "{}"));
    }

    private void at(JournalEntry entry, LocalDateTime seoul) {
        update("journal_entry", "created_at", entry.getId(), seoul);
    }

    private void atMatching(MatchingResult result, LocalDateTime seoul) {
        update("matching_result", "created_at", result.getId(), seoul);
    }

    private void atValidation(ValidationResult result, LocalDateTime seoul) {
        update("validation_result", "created_at", result.getId(), seoul);
    }

    private void atSummary(SummaryResult summary, LocalDateTime createdSeoul, LocalDateTime updatedSeoul) {
        update("summary_result", "created_at", summary.getId(), createdSeoul);
        update("summary_result", "updated_at", summary.getId(), updatedSeoul);
    }

    private void atInsight(InsightResult insight, LocalDateTime seoul) {
        update("insight_result", "created_at", insight.getId(), seoul);
    }

    /** 서울 시각을 JVM 시간대의 벽시계 시각으로 바꿔 넣는다 — 엔티티가 {@code LocalDateTime.now()} 로 남기는 것과 같다. */
    private void update(String table, String column, Long id, LocalDateTime seoul) {
        LocalDateTime local = seoul.atZone(MutableClock.KST).withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();
        em.getEntityManager().createNativeQuery("update " + table + " set " + column + " = ?1 where id = ?2")
                .setParameter(1, local)
                .setParameter(2, id)
                .executeUpdate();
        em.clear();
    }
}
