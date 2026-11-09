package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.service.insight.InsightWeekStageReader.Stage;
import com.itda.backend.service.insight.InsightWeekStageReader.Week;
import com.itda.backend.service.insight.InsightWeekStageReader.WeekStage;

/** 대시보드 5단계(분석 승인) 판정 (#370). 승인 주체는 학교 &gt; 센터 &gt; 활동지원사다 ({@link PrimarySource}). */
@DataJpaTest
@ActiveProfiles("test")
@Import(InsightWeekStageReader.class)
class InsightWeekStageReaderTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 26);
    private static final LocalDate TO = LocalDate.of(2026, 11, 1);
    private static final Long CHILD = 8L;
    private static final Week WEEK = new Week(CHILD, FROM, TO);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private InsightWeekStageReader reader;

    private Long center;
    private Long school;

    @BeforeEach
    void setUp() {
        center = em.persist(OrganizationFixture.center()).getId();
        school = em.persist(Organization.of("해솔초등학교", OrganizationType.SCHOOL,
                OrganizationFixture.nextBusinessNumber())).getId();
    }

    @Test
    void 우리가_승인_주체인_대기_분석이면_여기서_승인할_차례다() {
        InsightResult insight = insight(1, "[" + center + "]");

        assertThat(reader.stagesOf(center, List.of(WEEK)))
                .containsExactly(Map.entry(WEEK, new WeekStage(Stage.REVIEW_HERE, insight.getId(), null)));
    }

    @Test
    void 우리가_보류한_분석이면_보류_상태다() {
        InsightResult insight = insight(1, "[" + center + "]");
        insight.holdGate2();
        em.persistAndFlush(insight);

        assertThat(reader.stagesOf(center, List.of(WEEK)).get(WEEK))
                .isEqualTo(new WeekStage(Stage.INSIGHT_HELD, insight.getId(), null));
    }

    @Test
    void 학교가_함께_근거를_냈으면_학교가_승인할_차례고_이름을_알려준다() {
        insight(1, "[" + center + "," + school + "]");

        assertThat(reader.stagesOf(center, List.of(WEEK)).get(WEEK))
                .isEqualTo(new WeekStage(Stage.REVIEW_ELSEWHERE, null, "해솔초등학교"));
    }

    @Test
    void 모두_승인됐으면_끝이다() {
        InsightResult insight = insight(1, "[" + center + "]");
        insight.approveGate2();
        em.persistAndFlush(insight);

        assertThat(reader.stagesOf(center, List.of(WEEK)).get(WEEK))
                .isEqualTo(new WeekStage(Stage.APPROVED, null, null));
    }

    @Test
    void 같은_주는_마지막_회차만_본다() {
        InsightResult old = insight(1, "[" + center + "]");
        InsightResult latest = insight(2, "[" + center + "]");
        latest.approveGate2();
        em.persistAndFlush(latest);

        assertThat(old.getStatus().name()).isEqualTo("GATE2_PENDING");
        assertThat(reader.stagesOf(center, List.of(WEEK)).get(WEEK).stage()).isEqualTo(Stage.APPROVED);
    }

    @Test
    void 여기서_승인할_것과_다른_곳_차례가_섞이면_여기_차례를_먼저_보여준다() {
        insight(1, "[" + center + "," + school + "]");
        InsightResult mine = insight(1, "[" + center + "]");

        assertThat(reader.stagesOf(center, List.of(WEEK)).get(WEEK))
                .isEqualTo(new WeekStage(Stage.REVIEW_HERE, mine.getId(), null));
    }

    @Test
    void 우리_기록이_쓰이지_않은_주는_답하지_않는다() {
        insight(1, "[" + school + "]");

        assertThat(reader.stagesOf(center, List.of(WEEK))).isEmpty();
    }

    /**
     * (아이 8, 이번 주) · (아이 9, 다음 주)를 물으면 묻지 않은 (아이 8, 다음 주)도 조회 후보에 든다 (#493). 답에는 물은 조합만
     * 남아야 한다.
     */
    @Test
    void 물은_아이와_주의_조합만_답한다() {
        Week other = new Week(9L, FROM.plusWeeks(1), TO.plusWeeks(1));
        Week notAsked = new Week(CHILD, FROM.plusWeeks(1), TO.plusWeeks(1));
        InsightResult mine = insight(1, "[" + center + "]");
        InsightResult others = insight(other, 1, "[" + center + "]");
        insight(notAsked, 1, "[" + center + "]");

        assertThat(reader.stagesOf(center, List.of(WEEK, other))).containsOnly(
                Map.entry(WEEK, new WeekStage(Stage.REVIEW_HERE, mine.getId(), null)),
                Map.entry(other, new WeekStage(Stage.REVIEW_HERE, others.getId(), null)));
    }

    @Test
    void 물은_주_밖의_회차는_답에_영향을_주지_않는다() {
        Week next = new Week(CHILD, FROM.plusWeeks(1), TO.plusWeeks(1));
        InsightResult mine = insight(1, "[" + center + "]");
        // 다음 주에는 회차가 더 많고 모두 승인됐다. 이번 주의 마지막 회차(1)를 가리면 안 된다
        for (int revision = 1; revision <= 3; revision++) {
            InsightResult later = insight(next, revision, "[" + center + "]");
            later.approveGate2();
            em.persistAndFlush(later);
        }

        assertThat(reader.stagesOf(center, List.of(WEEK)))
                .containsExactly(Map.entry(WEEK, new WeekStage(Stage.REVIEW_HERE, mine.getId(), null)));
    }

    private InsightResult insight(int revision, String sourceInstitutionIds) {
        return insight(WEEK, revision, sourceInstitutionIds);
    }

    private InsightResult insight(Week week, int revision, String sourceInstitutionIds) {
        return em.persistAndFlush(InsightResult.of(week.childId(), week.from(), week.to(), revision, "상황", "반응",
                "본문", "[]", "[]", "[]", sourceInstitutionIds, sourceInstitutionIds.contains(","), "{}"));
    }
}
