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

    private InsightResult insight(int revision, String sourceInstitutionIds) {
        return em.persistAndFlush(InsightResult.of(CHILD, FROM, TO, revision, "상황", "반응", "본문", "[]", "[]",
                "[]", sourceInstitutionIds, sourceInstitutionIds.contains(","), "{}"));
    }
}
