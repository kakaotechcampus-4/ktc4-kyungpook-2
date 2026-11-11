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
import org.springframework.jdbc.core.JdbcTemplate;
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

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "app.insight.worker.enabled=true",
        "app.insight.worker.initial-delay-ms=2147483647",
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

        @Bean
        RestClient insightAgentRestClient(RestClient.Builder stubAgentBuilder, MockRestServiceServer agentServer) {
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
    @Autowired private JdbcTemplate jdbcTemplate;

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

    @Test
    void 재료가_늘지_않으면_다시_집지_않는다() {
        approved(school, periodFrom.plusDays(1), claims("혼자 신발을 신었다.", 1041, "혼자 신음"));

        agentHealthy();
        agentServer.expect(requestTo(AI + "/insight")).andRespond(withSuccess("""
                {"child_id": 1, "insights": [], "llm_called": true}
                """, MediaType.APPLICATION_JSON));
        worker.run();

        agentServer.reset();
        agentHealthy();
        worker.run();

        assertThat(insightRunRepository.findAll()).hasSize(1);
        agentServer.verify();
    }

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

        agentServer.reset();
        agentHealthy();
        worker.run();

        assertThat(insightRunRepository.findAll()).hasSize(1);
        agentServer.verify();
    }

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

    @Test
    void 옛_방식으로_고쳐_승인한_요약뿐이면_부르지도_집지도_않는다() {
        SummaryResult corrected =
                approved(school, periodFrom.plusDays(1), claims("추정이 섞인 문장.", 1041, "원문 인용"));
        approvalRepository.saveAndFlush(new Approval(ApprovalTargetType.SUMMARY, corrected.getId(),
                ApprovalReviewType.GATE1, 1L, ApprovalDecision.CORRECTED, "추정 표현을 뺐습니다"));

        agentHealthy();

        worker.run();

        assertThat(insightResultRepository.findAll()).isEmpty();
        assertThat(insightRunRepository.findAll()).isEmpty();
        agentServer.verify();
    }

    @Test
    void AI가_503이면_실패로_남고_기다렸다가_다음_판으로_다시_집는다() {
        approved(school, periodFrom.plusDays(1), claims("혼자 신발을 신었다.", 1041, "혼자 신음"));

        agentHealthy();
        agentServer.expect(requestTo(AI + "/insight"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\": {\"reason\": \"llm_unavailable\", \"failure\": \"empty_response\"}}"));

        worker.run();

        assertThat(insightRunRepository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo(InsightRunStatus.FAILED);
            assertThat(run.getInsightCount()).isNull();
            assertThat(run.getFailureReason()).isEqualTo("llm_unavailable:empty_response");
        });

        agentServer.reset();
        agentHealthy();
        worker.run();
        assertThat(insightRunRepository.findAll()).hasSize(1);

        insightRunRepository.findAll().forEach(run -> jdbcTemplate.update(
                "UPDATE insight_run SET updated_at = ? WHERE id = ?", run.getUpdatedAt().minusHours(1), run.getId()));
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
