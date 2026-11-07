package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.insight.InsightRun;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.response.insight.InsightProgressResponse;
import com.itda.backend.dto.response.insight.InsightProgressResponse.Status;
import com.itda.backend.service.agent.WorkerProperties;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

/** 인사이트 진행 현황 (O-43). 우리 기관이 승인한 주만, 워커의 집기 규칙과 같은 상태로 보인다. */
@DataJpaTest
@ActiveProfiles("test")
@Import({InsightProgressService.class, InsightResultRecorder.class, InsightService.class, ConsentStateReader.class,
        InsightProgressServiceTest.Config.class})
class InsightProgressServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String TEACHER = "99";
    /** 지난주 수요일. 끝난 주라 워커가 볼 수 있다. */
    private static final LocalDate LAST_WEEK = LocalDate.now(SEOUL).minusWeeks(1);
    private static final String CLAIMS = """
            [{"text": "블록을 혼자 다섯 층 쌓았다.",
              "evidence": [{"journal_entry_id": 1041, "quote": "혼자 다섯 층까지 쌓음",
                            "span": {"start": 0, "end": 12}}]}]
            """;

    @TestConfiguration
    static class Config {
        @Bean
        InsightProperties insightProperties() {
            return new InsightProperties(new WorkerProperties(true, 5000, 10), 7, SEOUL, 14, false, List.of(), Duration.ofSeconds(210), 1);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private TestEntityManager em;
    @Autowired
    private InsightProgressService service;
    @Autowired
    private InsightProperties properties;
    @MockitoBean
    private UserService userService;

    private Long childId;
    private Long centerId;
    private Long otherId;

    @BeforeEach
    void setUp() {
        childId = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2018, 3, 2))).getId();
        centerId = em.persistAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1000000002")).getId();
        otherId = em.persistAndFlush(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001")).getId();
        em.persistAndFlush(ChildOrganization.of(childId, centerId));
        em.persistAndFlush(ChildOrganization.of(childId, otherId));
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(centerId);
    }

    @Test
    void 승인했지만_아직_안_집었으면_곧_분석한다() {
        consent(centerId, ConsentField.WEEKLY_INSIGHT);
        approve(centerId, LAST_WEEK);

        InsightProgressResponse progress = service.getProgress(TEACHER).get(0);

        assertThat(progress.status()).isEqualTo(Status.QUEUED);
        assertThat(progress.message()).isEqualTo("곧 분석을 시작해요");
        assertThat(progress.childName()).isEqualTo("김하늘");
        assertThat(progress.periodTo()).isEqualTo(properties.periodEndOf(LAST_WEEK).toString());
    }

    @Test
    void 끝나지_않은_이번_주는_주가_끝나기를_기다린다() {
        consent(centerId, ConsentField.WEEKLY_INSIGHT);
        approve(centerId, LocalDate.now(SEOUL));

        assertThat(statuses()).containsExactly(Status.WAITING_WEEK_END);
    }

    @Test
    void 주간_분석_동의가_없으면_우리_기록은_쓰지_않는다고_알린다() {
        consent(centerId, ConsentField.DAILY_SUMMARY);
        approve(centerId, LAST_WEEK);
        run(LAST_WEEK).finish(2, Long.MAX_VALUE);

        assertThat(statuses()).containsExactly(Status.NOT_CONSENTED);
    }

    @Test
    void 실행_상태를_그대로_옮긴다() {
        consent(centerId, ConsentField.WEEKLY_INSIGHT);
        approve(centerId, LAST_WEEK);

        InsightRun first = run(LAST_WEEK);
        em.flush();
        assertThat(statuses()).containsExactly(Status.RUNNING);

        em.find(InsightRun.class, first.getId()).fail("timeout");
        em.flush();
        assertThat(statuses()).containsExactly(Status.RETRYING);
    }

    @Test
    void 끝났으면_건수로_검토_대기와_알릴_것_없음을_가른다() {
        consent(centerId, ConsentField.WEEKLY_INSIGHT);
        Long contextId = approve(centerId, LAST_WEEK);

        InsightRun run = run(LAST_WEEK);
        run.finish(0, contextId);
        em.flush();
        assertThat(statuses()).containsExactly(Status.NOTHING_NEW);

        InsightRun next = InsightRun.claim(childId, run.getPeriodFrom(), run.getPeriodTo(), 2);
        next.finish(1, contextId);
        em.persistAndFlush(next);
        assertThat(statuses()).containsExactly(Status.DONE);
    }

    @Test
    void 끝난_뒤에_새로_승인된_기록이_있으면_다시_분석할_차례다() {
        consent(centerId, ConsentField.WEEKLY_INSIGHT);
        Long contextId = approve(centerId, LAST_WEEK);
        run(LAST_WEEK).finish(1, contextId);
        // 다른 기관이 같은 주에 승인했다. 워커는 재료가 늘었다고 보고 다시 집는다.
        approve(otherId, LAST_WEEK);

        assertThat(statuses()).containsExactly(Status.QUEUED);
    }

    /** 워커는 재료가 없으면 집지 않는다. 그 주에 "곧 분석" · "다시 시도" 가 영영 남으면 안 된다. */
    @Test
    void 재료가_없으면_곧_분석이나_다시_시도라고_하지_않는다() {
        consent(centerId, ConsentField.WEEKLY_INSIGHT);
        approve(centerId, LAST_WEEK, "[]");
        assertThat(statuses()).containsExactly(Status.NOTHING_NEW);

        InsightRun failed = run(LAST_WEEK);
        failed.fail("timeout");
        em.flush();
        assertThat(statuses()).containsExactly(Status.NOTHING_NEW);
    }

    @Test
    void 다른_기관만_승인한_주나_동의가_꺼진_아이는_보이지_않는다() {
        approve(otherId, LAST_WEEK);
        assertThat(service.getProgress(TEACHER)).isEmpty();

        // 우리 기관이 승인했어도 공유 동의가 없으면 그 아이는 이 기관이 알 일이 아니다 (Gate 1 과 같은 기준).
        approve(centerId, LAST_WEEK);
        assertThat(service.getProgress(TEACHER)).isEmpty();
    }

    private List<Status> statuses() {
        em.clear();
        return service.getProgress(TEACHER).stream().map(InsightProgressResponse::status).toList();
    }

    private void consent(Long organizationId, ConsentField field) {
        em.persistAndFlush(SharingConsent.grant(childId, 7L, organizationId, List.of(field)));
    }

    /** 그 기관이 그날 기록을 Gate 1 에서 승인했다. 승인본 id 를 돌려준다. */
    private Long approve(Long organizationId, LocalDate entryDate) {
        return approve(organizationId, entryDate, CLAIMS);
    }

    private Long approve(Long organizationId, LocalDate entryDate, String claims) {
        SummaryResult summary = em.persistAndFlush(
                SummaryResult.of(childId, entryDate, organizationId, 1, "블록을 쌓았다.", claims, "[1041]", "[]", "{}"));
        return em.persistAndFlush(ChildContext.of(childId, summary.getId(), "블록을 쌓았다.")).getId();
    }

    private InsightRun run(LocalDate entryDate) {
        LocalDate to = properties.periodEndOf(entryDate);
        return em.persistAndFlush(InsightRun.claim(childId, properties.periodStartOf(to), to, 1));
    }
}
