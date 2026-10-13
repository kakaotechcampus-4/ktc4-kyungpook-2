package com.itda.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.ApprovalTargetType;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.request.matching.MatchingAgentRequest;
import com.itda.backend.dto.request.summary.SummaryAgentRequest;
import com.itda.backend.dto.request.validation.ValidationAgentRequest;
import com.itda.backend.dto.response.matching.MatchingAgentResponse;
import com.itda.backend.dto.response.summary.SummaryAgentResponse;
import com.itda.backend.dto.response.validation.ValidationAgentResponse;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.approval.Gate1Version;
import com.itda.backend.service.matching.MatchingAgentClient;
import com.itda.backend.service.matching.MatchingAgentReply;
import com.itda.backend.service.matching.MatchingProperties;
import com.itda.backend.service.matching.MatchingResultRecorder;
import com.itda.backend.service.matching.MatchingService;
import com.itda.backend.service.matching.MatchingWorker;
import com.itda.backend.service.summary.SummaryAgentClient;
import com.itda.backend.service.summary.SummaryAgentReply;
import com.itda.backend.service.summary.SummaryProperties;
import com.itda.backend.service.summary.SummaryResultRecorder;
import com.itda.backend.service.summary.SummaryService;
import com.itda.backend.service.summary.SummaryWorker;
import com.itda.backend.service.validation.ValidationAgentClient;
import com.itda.backend.service.validation.ValidationAgentReply;
import com.itda.backend.service.validation.ValidationProperties;
import com.itda.backend.service.validation.ValidationResultRecorder;
import com.itda.backend.service.validation.ValidationService;
import com.itda.backend.service.validation.ValidationWorker;

import jakarta.servlet.http.Cookie;

/**
 * 파일 업로드부터 Gate 1 까지 한 번에 돌린다 — 업로드 → 매칭 → 검증 → 지금 요약 → 요약 → Gate 1 목록·결정.
 *
 * <p>AI 호출만 가짜로 바꾸고 나머지(API, 워커, 서비스, 레코더, PostgreSQL)는 실제 것을 쓴다. 워커는 테스트 프로필에서
 * 꺼져 있어 스케줄러가 돌지 않으므로 직접 만들어 한 차례씩 돌린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class Gate1PipelineIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    private static final String BLOCKS = "준호가 블록을 다섯 층까지 쌓았다.";
    private static final String PUSHED = "준호가 친구를 밀었다.";
    private static final String NAP = "준호가 낮잠을 잤다.";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_gate1_pipeline_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        String storage = Files.createTempDirectory("gate1-pipeline-raw").toString();
        registry.add("app.raw-storage.base-dir", () -> storage);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private SharingConsentRepository sharingConsentRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private SummaryResultRepository summaryResultRepository;
    @Autowired private ChildContextRepository childContextRepository;
    @Autowired private ApprovalRepository approvalRepository;

    @Autowired private MatchingService matchingService;
    @Autowired private MatchingResultRecorder matchingResultRecorder;
    @Autowired private MatchingProperties matchingProperties;
    @Autowired private ValidationService validationService;
    @Autowired private ValidationResultRecorder validationResultRecorder;
    @Autowired private ValidationProperties validationProperties;
    @Autowired private SummaryService summaryService;
    @Autowired private SummaryResultRecorder summaryResultRecorder;
    @Autowired private SummaryProperties summaryProperties;

    private User teacher;
    private Child junho;
    private MatchingWorker matchingWorker;
    private ValidationWorker validationWorker;
    private SummaryWorker summaryWorker;

    @BeforeEach
    void setUp() {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        teacher = userRepository.save(UserFixture.organizationUser(
                "pipeline-" + UUID.randomUUID(), "이선생", organization.getId()));
        junho = childRepository.save(Child.of("김준호", LocalDate.of(2020, 3, 1)));
        childOrganizationRepository.save(ChildOrganization.of(junho.getId(), organization.getId()));
        // 매칭 명부는 이 기관에 살아 있는 동의가 있는 아이만 받는다 (DB 스키마 §5.1).
        User guardian = userRepository.save(UserFixture.parent("parent-" + UUID.randomUUID(), "김보호"));
        sharingConsentRepository.save(SharingConsent.grant(junho.getId(), guardian.getId(), organization.getId(),
                List.of(ConsentField.DAILY_SUMMARY)));

        matchingWorker = new MatchingWorker(matchingService, fakeMatchingAgent(), matchingResultRecorder,
                matchingProperties);
        validationWorker = new ValidationWorker(validationService, fakeValidationAgent(), validationResultRecorder,
                validationProperties);
        summaryWorker = new SummaryWorker(summaryService, fakeSummaryAgent(), summaryResultRecorder,
                summaryProperties);
    }

    @Test
    void 올린_일지가_요약돼_Gate1_에서_고쳐_승인되고_뒤에_온_일지는_새_판으로_쌓인다() throws Exception {
        upload("2026-10-08 " + BLOCKS + "\n2026-10-08 " + PUSHED + "\n");
        runPipeline();

        // 목록: 요약 본문, 요약에 들어간 원본의 REVIEW 사유(근거 구간과 겹침)
        Long blocks = entryIdOf(BLOCKS);
        Long pushed = entryIdOf(PUSHED);
        String listed = pendingList()
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].childName").value("김준호"))
                .andExpect(jsonPath("$.data[0].content").value(BLOCKS + " " + PUSHED))
                .andExpect(jsonPath("$.data[0].uncoveredEntries", hasSize(0)))
                .andExpect(jsonPath("$.data[0].reviewFlags", hasSize(1)))
                .andExpect(jsonPath("$.data[0].reviewFlags[0].journalEntryId").value(pushed))
                .andExpect(jsonPath("$.data[0].reviewFlags[0].issueTypes[0]").value("위험행동표현"))
                .andReturn().getResponse().getContentAsString();
        Long id = ((Number) JsonPath.read(listed, "$.data[0].id")).longValue();
        String version = JsonPath.read(listed, "$.data[0].version");

        // 고쳐서 승인
        String edited = BLOCKS + " 친구와 부딪혔다.";
        mockMvc.perform(withAuth(post("/api/v1/summaries/" + id + "/gate1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("decision", "approve", "editedContent", edited, "version", version))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.decision").value("CORRECTED"));

        assertThat(childContextRepository.findByChildIdOrderByIdDesc(junho.getId())).singleElement()
                .satisfies(c -> assertThat(c.getContent()).isEqualTo(edited));
        assertThat(statusOf(blocks)).isEqualTo(JournalEntryStatus.COMPLETED);
        assertThat(statusOf(pushed)).isEqualTo(JournalEntryStatus.COMPLETED);
        assertThat(approvalRepository.findByTargetTypeAndTargetId(ApprovalTargetType.SUMMARY, id))
                .extracting(Approval::getDecision).containsExactly(ApprovalDecision.CORRECTED);
        pendingList().andExpect(jsonPath("$.data", hasSize(0)));

        // 같은 날 일지가 뒤늦게 오면 승인된 판은 두고 새 일지만 새 판으로 요약한다.
        upload("2026-10-08 " + NAP + "\n");
        runPipeline();

        Long nap = entryIdOf(NAP);
        SummaryResult second = latestSummary();
        assertThat(second.getRevision()).isEqualTo(2);
        assertThat(second.getContent()).isEqualTo(NAP);
        assertThat(second.getCoveredEntryIds()).isEqualTo("[" + nap + "]");
        assertThat(statusOf(blocks)).isEqualTo(JournalEntryStatus.COMPLETED);
        assertThat(summaryResultRepository.findById(id).orElseThrow().getStatus()).isEqualTo(SummaryStatus.APPROVED);
        pendingList()
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].revision").value(2));
    }

    @Test
    void 보류한_요약은_보류함에서_해제하거나_바로_제거한다() throws Exception {
        upload("2026-10-08 " + BLOCKS + "\n");
        runPipeline();
        Long blocks = entryIdOf(BLOCKS);
        Long summaryId = latestSummary().getId();

        decide(summaryId, "hold").andExpect(jsonPath("$.data.status").value("HOLD"));
        assertThat(statusOf(blocks)).isEqualTo(JournalEntryStatus.GATE1_HELD);
        pendingList().andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(withAuth(get("/api/v1/summaries")).param("gate1Status", "hold"))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(summaryId));

        // 보류된 채로는 승인하지 않는다 — 보류를 해제한 뒤 검토 목록에서 승인한다.
        decide(summaryId, "approve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUMMARY_HELD"));

        mockMvc.perform(withAuth(post("/api/v1/summaries/" + summaryId + "/reopen")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("GATE1_PENDING"));
        assertThat(statusOf(blocks)).isEqualTo(JournalEntryStatus.GATE1_PENDING);

        // 다시 보류해 보류함에서 바로 제거한다.
        decide(summaryId, "hold").andExpect(jsonPath("$.data.status").value("HOLD"));
        decide(summaryId, "remove").andExpect(jsonPath("$.data.status").value("REMOVED"));
        assertThat(journalEntryRepository.findById(blocks).orElseThrow().isDeleted()).isTrue();
        decide(summaryId, "approve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUMMARY_ALREADY_REVIEWED"));
        assertThat(approvalRepository.findByTargetTypeAndTargetId(ApprovalTargetType.SUMMARY, summaryId))
                .extracting(Approval::getDecision)
                .containsExactlyInAnyOrder(ApprovalDecision.HOLD, ApprovalDecision.REOPENED, ApprovalDecision.HOLD,
                        ApprovalDecision.REMOVED);
    }

    // ── 흐름 ──

    private void upload(String text) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "journal.txt", "text/plain",
                text.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(withAuth(multipart("/api/v1/raw-records").file(file)))
                .andExpect(status().isCreated());
    }

    /** 매칭 → 검증 → 지금 요약 → 요약. 마감(다음 날 03:00)·디바운스(30분)는 지금 요약으로 건너뛴다. */
    private void runPipeline() throws Exception {
        matchingWorker.run();
        validationWorker.run();
        mockMvc.perform(withAuth(post("/api/v1/summaries/run"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"childId\": %d, \"entryDate\": \"%s\"}".formatted(junho.getId(), DATE)))
                .andExpect(status().isOk());
        summaryWorker.run();
    }

    private org.springframework.test.web.servlet.ResultActions pendingList() throws Exception {
        return mockMvc.perform(withAuth(get("/api/v1/summaries"))).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions decide(Long summaryId, String decision)
            throws Exception {
        return mockMvc.perform(withAuth(post("/api/v1/summaries/" + summaryId + "/gate1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\": \"%s\", \"version\": \"%s\"}".formatted(decision,
                        Gate1Version.of(summaryResultRepository.findById(summaryId).orElseThrow(),
                                journalEntryRepository.findBySummaryIdAndDeletedAtIsNull(summaryId)))));
    }

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request) throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        Cookie auth = new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(teacher.getId())));
        return request.cookie(auth, csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private Long entryIdOf(String content) {
        return journalEntryRepository.findAll().stream()
                .filter(e -> content.equals(e.getContent()) && junho.getId().equals(e.getChildId()))
                .map(JournalEntry::getId)
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    private JournalEntryStatus statusOf(Long journalEntryId) {
        return journalEntryRepository.findById(journalEntryId).orElseThrow().getStatus();
    }

    private SummaryResult latestSummary() {
        return summaryResultRepository.findFirstByChildIdAndEntryDateAndInstitutionIdOrderByRevisionDesc(
                junho.getId(), DATE, teacher.getOrganizationId()).orElseThrow();
    }

    // ── 가짜 AI ──

    /** 명부에 준호가 있으면 준호로 자동 확정한다. 동의가 없어 명부에서 빠지면 매칭되지 않는다. */
    private MatchingAgentClient fakeMatchingAgent() {
        MatchingAgentClient client = mock(MatchingAgentClient.class);
        given(client.isAvailable()).willReturn(true);
        given(client.match(any())).willAnswer(invocation -> {
            MatchingAgentRequest request = invocation.getArgument(0);
            boolean onRoster = request.roster().stream().anyMatch(r -> junho.getId().equals(r.childId()));
            ObjectNode json = objectMapper.createObjectNode()
                    .put("journal_entry_id", request.journalEntryId())
                    .put("status", onRoster ? "auto" : "unmatched");
            if (onRoster) {
                json.put("matched_child_id", junho.getId());
            }
            json.put("confidence", 0.98).put("hint_mismatch", false).put("llm_called", true);
            json.putArray("evidence");
            json.putArray("mentioned_child_ids");
            json.putArray("candidates");
            return new MatchingAgentReply(objectMapper.treeToValue(json, MatchingAgentResponse.class), json.toString());
        });
        return client;
    }

    /** "밀었다" 가 있으면 앞부분을 짚어 REVIEW, 아니면 PASS. */
    private ValidationAgentClient fakeValidationAgent() {
        ValidationAgentClient client = mock(ValidationAgentClient.class);
        given(client.isAvailable()).willReturn(true);
        given(client.validate(any())).willAnswer(invocation -> {
            ValidationAgentRequest request = invocation.getArgument(0);
            boolean review = request.content().contains("밀었다");
            ObjectNode json = objectMapper.createObjectNode()
                    .put("journal_entry_id", request.journalEntryId())
                    .put("verdict", review ? "REVIEW" : "PASS");
            ArrayNode types = json.putArray("issue_types");
            ArrayNode evidence = json.putArray("evidence");
            if (review) {
                types.add("위험행동표현");
                evidence.addObject().put("start", 2).put("end", 6);
            }
            return new ValidationAgentReply(objectMapper.treeToValue(json, ValidationAgentResponse.class),
                    json.toString());
        });
        return client;
    }

    /** 일지마다 원문 그대로 한 문장을 쓰고, 앞 네 글자를 근거로 단다. 본문은 문장을 이은 것이다. */
    private SummaryAgentClient fakeSummaryAgent() {
        SummaryAgentClient client = mock(SummaryAgentClient.class);
        given(client.isAvailable()).willReturn(true);
        given(client.summarize(any())).willAnswer(invocation -> {
            SummaryAgentRequest request = invocation.getArgument(0);
            ObjectNode json = objectMapper.createObjectNode()
                    .put("child_id", request.childId())
                    .put("entry_date", request.entryDate())
                    .put("institution_id", request.institutionId())
                    .put("content", request.sources().stream().map(SummaryAgentRequest.Source::content)
                            .collect(Collectors.joining(" ")))
                    .put("needs_review", false)
                    .put("llm_called", true);
            ArrayNode claims = json.putArray("claims");
            ArrayNode covered = json.putArray("covered_entry_ids");
            json.putArray("uncovered_entry_ids");
            json.putArray("review_reasons");
            for (SummaryAgentRequest.Source source : request.sources()) {
                covered.add(source.journalEntryId());
                ObjectNode claim = claims.addObject().put("text", source.content());
                claim.putArray("evidence").addObject()
                        .put("journal_entry_id", source.journalEntryId())
                        .put("quote", source.content().substring(0, 4))
                        .putObject("span").put("start", 0).put("end", 4);
            }
            return new SummaryAgentReply(toSummaryResponse(json), json.toString());
        });
        return client;
    }

    private SummaryAgentResponse toSummaryResponse(ObjectNode json) throws JsonProcessingException {
        return objectMapper.treeToValue(json, SummaryAgentResponse.class);
    }
}
