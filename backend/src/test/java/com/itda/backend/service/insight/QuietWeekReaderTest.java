package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightRun;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.service.agent.WorkerProperties;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.insight.QuietWeekReader.QuietWeek;
import com.itda.backend.service.insight.QuietWeekReader.Reason;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuietWeekReader.class, InsightResultRecorder.class, ConsentStateReader.class, QuietWeekReaderTest.Config.class})
class QuietWeekReaderTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate LAST_WEEK = LocalDate.now(SEOUL).minusWeeks(1);

    @TestConfiguration
    static class Config {
        @Bean
        InsightProperties insightProperties() {
            return new InsightProperties(new WorkerProperties(true, 5000, 10), 7, SEOUL, 14, false, List.of(),
                    Duration.ofSeconds(210), 1);
        }
    }

    @Autowired
    private TestEntityManager em;
    @Autowired
    private QuietWeekReader reader;
    @Autowired
    private InsightProperties properties;

    private Long childId;
    private Long centerId;
    private Long schoolId;

    @BeforeEach
    void setUp() {
        childId = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2018, 3, 2))).getId();
        centerId = em.persistAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1000000002")).getId();
        schoolId = em.persistAndFlush(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001")).getId();
        em.persistAndFlush(ChildOrganization.of(childId, centerId));
        em.persistAndFlush(ChildOrganization.of(childId, schoolId));
        em.persistAndFlush(SharingConsent.grant(childId, 7L, centerId, List.of(ConsentField.WEEKLY_INSIGHT)));
    }

    @Test
    void 기록이_모두_특이사항_없음으로_확인된_지난주는_특이사항_없음이다() {
        confirmNoContent(centerId, LAST_WEEK);

        assertThat(read()).singleElement().satisfies(week -> {
            assertThat(week.reason()).isEqualTo(Reason.NOTHING_NOTABLE);
            assertThat(week.periodTo()).isEqualTo(properties.periodEndOf(LAST_WEEK));
            assertThat(week.periodFrom()).isEqualTo(properties.periodStartOf(week.periodTo()));
        });
    }

    @Test
    void 승인된_기록이_있으면_분석이_끝나_0건일_때만_알릴_것_없음이다() {
        Long contextId = approve(centerId, LAST_WEEK);
        assertThat(read()).isEmpty();

        InsightRun run = run(LAST_WEEK);
        assertThat(read()).isEmpty();

        em.find(InsightRun.class, run.getId()).finish(0, contextId);
        em.flush();
        assertThat(read()).extracting(QuietWeek::reason).containsExactly(Reason.NOTHING_NEW);

        approve(schoolId, LAST_WEEK);
        assertThat(read()).isEmpty();
    }

    @Test
    void 그_주_인사이트가_있으면_보류만_있어도_빈칸이다() {
        Long contextId = approve(centerId, LAST_WEEK);
        run(LAST_WEEK).finish(1, contextId);
        LocalDate to = properties.periodEndOf(LAST_WEEK);
        InsightResult insight = em.persistAndFlush(InsightResult.of(childId, properties.periodStartOf(to), to, 1,
                "급식실 입장 직후", "소리를 지름", "이동 전 5분 예고가 효과적이었다.", null, "[]", "[]",
                "[%d]".formatted(centerId), false, null));
        insight.holdGate2();
        em.flush();

        assertThat(read()).isEmpty();
    }

    @Test
    void 기록이_없거나_허락받은_기관의_결정이_없으면_빈칸이다() {
        assertThat(read()).isEmpty();

        confirmNoContent(schoolId, LAST_WEEK);
        assertThat(read()).isEmpty();
    }

    @Test
    void 안_끝난_일지나_검토_전_요약이_있으면_빈칸이다() {
        confirmNoContent(centerId, LAST_WEEK);
        JournalEntry waiting = JournalEntry.of(1L, LAST_WEEK, "점심을 다 먹음", 1);
        waiting.assignChild(childId);
        Long waitingId = em.persistAndFlush(waiting).getId();
        assertThat(read()).isEmpty();

        em.find(JournalEntry.class, waitingId).delete();
        em.flush();
        em.persistAndFlush(SummaryResult.of(childId, LAST_WEEK, schoolId, 1, "블록을 쌓았다.", "[]", "[1]", "[]", "{}"));
        assertThat(read()).isEmpty();
    }

    @Test
    void 날짜_없는_일지는_어느_주도_막지_않는다() {
        confirmNoContent(centerId, LAST_WEEK);
        JournalEntry undated = JournalEntry.of(1L, null, "점심을 다 먹음", 1);
        undated.assignChild(childId);
        em.persistAndFlush(undated);

        assertThat(read()).extracting(QuietWeek::reason).containsExactly(Reason.NOTHING_NOTABLE);
    }

    @Test
    void 끝나지_않은_이번_주는_빈칸이다() {
        confirmNoContent(centerId, LocalDate.now(SEOUL));

        assertThat(read()).isEmpty();
    }

    private List<QuietWeek> read() {
        em.clear();
        return reader.read(List.of(childId));
    }

    private void confirmNoContent(Long organizationId, LocalDate entryDate) {
        SummaryResult summary = SummaryResult.noContent(childId, entryDate, organizationId, 1, "[1]", "{}");
        summary.confirmNoContent();
        em.persistAndFlush(summary);
    }

    private Long approve(Long organizationId, LocalDate entryDate) {
        SummaryResult summary = SummaryResult.of(childId, entryDate, organizationId, 1, "블록을 쌓았다.", "[]", "[1]", "[]",
                "{}");
        summary.approve();
        em.persistAndFlush(summary);
        return em.persistAndFlush(ChildContext.of(childId, summary.getId(), "블록을 쌓았다.")).getId();
    }

    private InsightRun run(LocalDate entryDate) {
        LocalDate to = properties.periodEndOf(entryDate);
        return em.persistAndFlush(InsightRun.claim(childId, properties.periodStartOf(to), to, 1));
    }
}
