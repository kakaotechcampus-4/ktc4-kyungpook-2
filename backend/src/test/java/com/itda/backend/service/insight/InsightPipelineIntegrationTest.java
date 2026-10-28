package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.ApprovalReviewType;
import com.itda.backend.domain.approval.ApprovalTargetType;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightRunStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.insight.InsightRunRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;

/**
 * 인사이트 워커 한 바퀴 — 승인된 요약이 있는 상태에서 워커를 돌려 {@code insight_result} 가 쌓이고
 * {@code insight_run} 이 닫히는 데까지.
 *
 * <p>AI 응답만 세워 두고 나머지는 실제 코드와 실제 PostgreSQL 이다. H2 로는 UNIQUE 위반이 운영과
 * 다른 모양으로 올라와서 집기 동작을 믿을 수 없다.
 *
 * <p>이 테스트가 덮지 못하는 것은 <b>모델이 실제로 쓸 만한 글을 내는가</b> 하나다. 그건 AI 쪽 평가의
 * 몫이고, BE 책임은 "제대로 보내고 제대로 받아 적는가" 인데 그건 여기서 전부 확인된다.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        // 테스트 프로필은 워커를 꺼 두지만, 이 테스트는 워커를 직접 돌리는 것이 목적이다.
        "app.insight.worker.enabled=true",
        // 켜면 @Scheduled 틱도 돈다. 틱이 AI /health 를 부르는 순간이 agentServer.reset() 과 expect() 사이에
        // 끼면 MockRestServiceServer 가 IllegalStateException 을 던진다 (CI 에서 실제로 났다). 간격만 늘리면
        // 앱이 뜰 때의 첫 틱이 남으므로 첫 실행 자체를 늦춰 틱이 아예 돌지 않게 하고, 워커는 테스트가 직접 돌린다.
        "app.insight.worker.initial-delay-ms=2147483647",
        // 재시도는 AiAgentClientTest 가 본다. 여기서 켜 두면 503 한 건에 7초를 기다린다.
        "app.ai.retry-backoffs="})
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@Import(InsightPipelineIntegrationTest.StubAgentConfig.class)
class InsightPipelineIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long GUARDIAN = 77L;
    private static final String AI = "http://ai:8000";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_insight_pipeline_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    /** AI 서버만 세워 둔다. 워커·재료 조립·저장은 전부 실제 코드다. */
    @TestConfiguration
    static class StubAgentConfig {
        @Bean
        RestClient.Builder stubAgentBuilder() {
            return RestClient.builder().baseUrl(AI);
        }

        @Bean
        MockRestServiceServer agentServer(RestClient.Builder stubAgentBuilder) {
            return MockRestServiceServer.bindTo(stubAgentBuilder).ignoreExpectOrder(true).build();
        }

        @Bean
        @Primary
        RestClient aiAgentRestClient(RestClient.Builder stubAgentBuilder, MockRestServiceServer agentServer) {
            return stubAgentBuilder.build();
        }
    }

    @Autowired private InsightWorker worker;
    @Autowired private MockRestServiceServer agentServer;
    @Autowired private ChildRepository childRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private SummaryResultRepository summaryResultRepository;
    @Autowired private ChildContextRepository childContextRepository;
    @Autowired private ApprovalRepository approvalRepository;
    @Autowired private InsightResultRepository insightResultRepository;
    @Autowired private InsightRunRepository insightRunRepository;
    @Autowired private SharingConsentRepository sharingConsentRepository;

    private Child junho;
    private Organization school;
    private Organization center;
    private LocalDate periodTo;
    private LocalDate periodFrom;

    @BeforeEach
    void setUp() {
        insightResultRepository.deleteAll();
        insightRunRepository.deleteAll();
        childContextRepository.deleteAll();
        approvalRepository.deleteAll();
        summaryResultRepository.deleteAll();
        childOrganizationRepository.deleteAll();
        childRepository.deleteAll();
        organizationRepository.deleteAll();
        sharingConsentRepository.deleteAll();
        agentServer.reset();

        periodTo = LocalDate.now(SEOUL).with(DayOfWeek.SUNDAY).minusWeeks(1);
        periodFrom = periodTo.minusDays(6);

        junho = childRepository.save(Child.of("김준호", LocalDate.of(2019, 3, 1)));
        school = organizationRepository.save(Organization.of("햇살초등학교", OrganizationType.SCHOOL, "1234567890"));
        center = organizationRepository.save(
                Organization.of("햇살아동발달센터", OrganizationType.CENTER, "1234567891"));
        childOrganizationRepository.save(ChildOrganization.of(junho.getId(), school.getId()));
        childOrganizationRepository.save(ChildOrganization.of(junho.getId(), center.getId()));
        // 재료는 "동의 ∩ 명부" 다 (#184). 동의가 없으면 아무 claim 도 쓰지 않는다 — 모르면 막는다.
        consent(school);
        consent(center);
    }

    private void consent(Organization institution) {
        sharingConsentRepository.save(SharingConsent.grant(junho.getId(), GUARDIAN, institution.getId(),
                List.of(ConsentField.DAILY_SUMMARY, ConsentField.WEEKLY_INSIGHT)));
    }

    private String claims(String text, long journalEntryId, String quote) {
        return """
                [{"text": "%s",
                  "evidence": [{"journal_entry_id": %d, "quote": "%s",
                                "span": {"start": 0, "end": 5}}]}]
                """.formatted(text, journalEntryId, quote);
    }

    /** Gate 1 을 통과한 요약 한 건. child_context 에 행이 있다는 것이 곧 승인이다. */
    private SummaryResult approved(Organization institution, LocalDate entryDate, String claims) {
        SummaryResult summary = summaryResultRepository.saveAndFlush(SummaryResult.of(
                junho.getId(), entryDate, institution.getId(), 1, "요약 본문", claims, "[1041]", "[]", "{}"));
        childContextRepository.saveAndFlush(
                ChildContext.of(junho.getId(), summary.getId(), "교사가 승인한 본문"));
        return summary;
    }

    private void agentHealthy() {
        agentServer.expect(requestTo(AI + "/health"))
                .andRespond(withSuccess("{\"status\": \"ok\"}", MediaType.APPLICATION_JSON));
    }

    @Test
    void 승인된_요약이_인사이트로_저장되고_실행이_닫힌다() {
        approved(school, periodFrom.plusDays(1), claims("점심 줄에서 차례를 기다렸다.", 1041, "차례를 기다림"));
        approved(center, periodFrom.plusDays(3), claims("점심 줄에서 끼어들었다.", 1042, "앞으로 끼어듦"));

        agentHealthy();
        agentServer.expect(requestTo(AI + "/insight")).andRespond(withSuccess("""
                {"child_id": 1, "period_from": "2026-10-05", "period_to": "2026-10-11",
                 "insights": [
                   {"situation": "점심 줄에서 기다릴 때",
                    "behavior": "기관에 따라 반응이 달랐다",
                    "content": "학교에서는 차례를 기다렸고, 센터에서는 끼어들었다.",
                    "supports": [],
                    "claim_ids": ["1-0", "2-0"],
                    "source_entry_ids": [1041, 1042],
                    "source_institution_ids": [1, 2],
                    "cross_institution": true}],
                 "llm_called": true}
                """, MediaType.APPLICATION_JSON));

        worker.run();

        assertThat(insightResultRepository.findAll()).singleElement().satisfies(result -> {
            assertThat(result.getChildId()).isEqualTo(junho.getId());
            assertThat(result.getPeriodFrom()).isEqualTo(periodFrom);
            assertThat(result.getPeriodTo()).isEqualTo(periodTo);
            assertThat(result.getRevision()).isEqualTo(1);
            assertThat(result.getContent()).isEqualTo("학교에서는 차례를 기다렸고, 센터에서는 끼어들었다.");
            assertThat(result.isCrossInstitution()).isTrue();
        });
        assertThat(insightRunRepository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo(InsightRunStatus.DONE);
            assertThat(run.getInsightCount()).isEqualTo(1);
        });
        agentServer.verify();
    }

    /**
     * 멘토 리뷰(#191) 지적 — Gate 1 승인은 교사가 틈틈이 한다. 한 주치가 한 번에 올라오지 않는다.
     * 인사이트를 만든 뒤 같은 기간에 요약이 더 승인되면 그 기록도 반영돼야 한다.
     */
    @Test
    void 같은_기간에_요약이_더_승인되면_다시_집는다() {
        approved(school, periodFrom.plusDays(1), claims("점심 줄에서 차례를 기다렸다.", 1041, "차례를 기다림"));

        agentHealthy();
        agentServer.expect(requestTo(AI + "/insight")).andRespond(withSuccess("""
                {"child_id": 1, "insights": [
                   {"situation": "점심 줄", "behavior": "기다렸다", "content": "차례를 기다렸다.",
                    "supports": [], "claim_ids": ["1-0"], "source_entry_ids": [1041],
                    "source_institution_ids": [1], "cross_institution": false}],
                 "llm_called": true}
                """, MediaType.APPLICATION_JSON));
        worker.run();

        assertThat(insightRunRepository.findAll()).singleElement()
                .satisfies(run -> assertThat(run.getInsightCount()).isEqualTo(1));

        // 수요일 — 같은 주의 다른 기관 요약이 뒤늦게 승인된다
        approved(center, periodFrom.plusDays(3), claims("점심 줄에서 끼어들었다.", 1042, "앞으로 끼어듦"));


        agentServer.reset();
        agentHealthy();
        agentServer.expect(requestTo(AI + "/insight")).andRespond(withSuccess("""
                {"child_id": 1, "insights": [
                   {"situation": "점심 줄에서 기다릴 때", "behavior": "기관에 따라 달랐다",
                    "content": "학교에서는 기다렸고 센터에서는 끼어들었다.",
                    "supports": [], "claim_ids": ["1-0", "2-0"], "source_entry_ids": [1041, 1042],
                    "source_institution_ids": [1, 2], "cross_institution": true}],
                 "llm_called": true}
                """, MediaType.APPLICATION_JSON));
        worker.run();

        assertThat(insightRunRepository.findAll()).hasSize(2);
        assertThat(insightResultRepository.findAll())
                .extracting(InsightResult::getRevision)
                .containsExactlyInAnyOrder(1, 2);
        agentServer.verify();
    }

    /** 늘지 않았으면 건너뛴다. 재호출 방지(§10.2-13)는 그대로 유지돼야 한다. */
    @Test
    void 재료가_늘지_않으면_다시_집지_않는다() {
        approved(school, periodFrom.plusDays(1), claims("혼자 신발을 신었다.", 1041, "혼자 신음"));

        agentHealthy();
        agentServer.expect(requestTo(AI + "/insight")).andRespond(withSuccess("""
                {"child_id": 1, "insights": [], "llm_called": true}
                """, MediaType.APPLICATION_JSON));
        worker.run();

        // 두 번째 차례 — /health 만 기대한다. /insight 가 또 불리면 verify 가 터진다.
        agentServer.reset();
        agentHealthy();
        worker.run();

        assertThat(insightRunRepository.findAll()).hasSize(1);
        agentServer.verify();
    }

    /** 이 장부를 둔 이유다. 두 번째 차례에는 AI 를 아예 부르지 않아야 한다. */
    @Test
    void 인사이트_0건으로_끝나면_다음_차례에_다시_부르지_않는다() {
        approved(school, periodFrom.plusDays(1), claims("혼자 블록을 쌓았다.", 1041, "혼자 쌓음"));

        agentHealthy();
        agentServer.expect(requestTo(AI + "/insight")).andRespond(withSuccess("""
                {"child_id": 1, "insights": [], "llm_called": true}
                """, MediaType.APPLICATION_JSON));

        worker.run();

        assertThat(insightResultRepository.findAll()).isEmpty();
        assertThat(insightRunRepository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo(InsightRunStatus.DONE);
            assertThat(run.getInsightCount()).isZero();
        });

        // 두 번째 차례 — /health 만 기대한다. /insight 가 또 불리면 verify 가 터진다.
        agentServer.reset();
        agentHealthy();
        worker.run();

        assertThat(insightRunRepository.findAll()).hasSize(1);
        agentServer.verify();
    }

    /**
     * 카드로 고쳐 승인한 요약이 워커를 거쳐 실제 AI 요청까지 간다 (#249, 멘토 리뷰 A4). 교사가 새로 쓴 문장은
     * 일지 번호가 없어도 빠지지 않고 null 로 실려야 한다 — 빠지면 교사 문장이 인사이트에 영영 못 들어간다.
     */
    @Test
    void 카드로_고쳐_승인한_요약은_출처를_달고_AI_요청까지_간다() {
        SummaryResult corrected = approved(school, periodFrom.plusDays(1), """
                [{"text": "점심 줄에서 차례를 기다렸다.", "source": "AI",
                  "evidence": [{"journal_entry_id": 1041, "quote": "줄에서 차례를 기다림"}]},
                 {"text": "손을 잡고 세어 주자 진정했다.", "source": "EDITED",
                  "evidence": [{"journal_entry_id": 1042, "quote": "손 잡고 셈"}]},
                 {"text": "약 먹은 날 차분했다.", "source": "TEACHER",
                  "evidence": [{"journal_entry_id": null, "quote": "약 먹은 날 차분했다."}]}]
                """);
        approvalRepository.saveAndFlush(new Approval(ApprovalTargetType.SUMMARY, corrected.getId(),
                ApprovalReviewType.GATE1, 1L, ApprovalDecision.CORRECTED, "카드로 고쳐 승인"));

        agentHealthy();
        agentServer.expect(requestTo(AI + "/insight"))
                .andExpect(jsonPath("$.claims[*].source").value(contains("AI", "EDITED", "TEACHER")))
                .andExpect(jsonPath("$.claims[2].evidence[0].journal_entry_id").value(nullValue()))
                .andRespond(withSuccess("""
                        {"child_id": 1, "insights": [], "llm_called": true}
                        """, MediaType.APPLICATION_JSON));

        worker.run();

        assertThat(insightRunRepository.findAll()).singleElement()
                .satisfies(run -> assertThat(run.getStatus()).isEqualTo(InsightRunStatus.DONE));
        agentServer.verify();
    }

    /**
     * #221 이전 방식으로 고쳐 승인한 요약(claims 에 source 가 없음)은 재료에서 빠진다 (#249). 재료가 없으면
     * 집지도 않는다 — 나중에 다른 요약이 승인되면 그때 다시 봐야 하기 때문이다.
     */
    @Test
    void 옛_방식으로_고쳐_승인한_요약뿐이면_부르지도_집지도_않는다() {
        SummaryResult corrected =
                approved(school, periodFrom.plusDays(1), claims("추정이 섞인 문장.", 1041, "원문 인용"));
        approvalRepository.saveAndFlush(new Approval(ApprovalTargetType.SUMMARY, corrected.getId(),
                ApprovalReviewType.GATE1, 1L, ApprovalDecision.CORRECTED, "추정 표현을 뺐습니다"));

        agentHealthy();

        worker.run();

        assertThat(insightResultRepository.findAll()).isEmpty();
        // 집지 않아야 한다 — 나중에 다른 요약이 승인되면 그때 다시 봐야 하기 때문이다.
        assertThat(insightRunRepository.findAll()).isEmpty();
        agentServer.verify();
    }

    /** 503 은 LLM 장애다. 실패로 남기고, 다음 차례에 다음 판으로 다시 집는다. */
    @Test
    void AI가_503이면_실패로_남고_다음_판으로_다시_집는다() {
        approved(school, periodFrom.plusDays(1), claims("혼자 신발을 신었다.", 1041, "혼자 신음"));

        agentHealthy();
        agentServer.expect(requestTo(AI + "/insight"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\": {\"reason\": \"llm_unavailable\"}}"));

        worker.run();

        assertThat(insightRunRepository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo(InsightRunStatus.FAILED);
            assertThat(run.getInsightCount()).isNull();
        });

        agentServer.reset();
        agentHealthy();
        agentServer.expect(requestTo(AI + "/insight")).andRespond(withSuccess("""
                {"child_id": 1, "insights": [
                   {"situation": "신발 신을 때", "behavior": "혼자 해냈다",
                    "content": "혼자 신발을 신었다.", "supports": [],
                    "claim_ids": ["1-0"], "source_entry_ids": [1041],
                    "source_institution_ids": [1], "cross_institution": false}],
                 "llm_called": true}
                """, MediaType.APPLICATION_JSON));

        worker.run();

        List<InsightResult> saved = insightResultRepository.findAll();
        assertThat(saved).singleElement()
                .satisfies(result -> assertThat(result.getRevision()).isEqualTo(2));
        assertThat(insightRunRepository.findAll()).hasSize(2);
        agentServer.verify();
    }

    /** AI 가 꺼져 있으면 아무도 집지 않는다 — 집어 두면 전부 실패로 끝난다. */
    @Test
    void AI가_꺼져_있으면_아무것도_집지_않는다() {
        approved(school, periodFrom.plusDays(1), claims("혼자 블록을 쌓았다.", 1041, "혼자 쌓음"));

        agentServer.expect(requestTo(AI + "/health"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        worker.run();

        assertThat(insightRunRepository.findAll()).isEmpty();
        assertThat(insightResultRepository.findAll()).isEmpty();
        agentServer.verify();
    }
}
