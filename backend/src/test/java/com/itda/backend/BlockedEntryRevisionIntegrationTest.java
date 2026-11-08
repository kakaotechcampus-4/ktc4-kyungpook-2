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
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itda.backend.service.agent.AiAvailability;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.request.matching.MatchingAgentRequest;
import com.itda.backend.dto.request.validation.ValidationAgentRequest;
import com.itda.backend.dto.response.matching.MatchingAgentResponse;
import com.itda.backend.dto.response.validation.ValidationAgentResponse;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.agent.AgentDownProperties;
import com.itda.backend.service.matching.MatchingAgentClient;
import com.itda.backend.service.matching.MatchingAgentReply;
import com.itda.backend.service.matching.MatchingProperties;
import com.itda.backend.service.matching.MatchingResultRecorder;
import com.itda.backend.service.matching.MatchingService;
import com.itda.backend.service.matching.MatchingWorker;
import com.itda.backend.service.validation.ValidationAgentClient;
import com.itda.backend.service.validation.ValidationAgentReply;
import com.itda.backend.service.validation.ValidationProperties;
import com.itda.backend.service.validation.ValidationResultRecorder;
import com.itda.backend.service.validation.ValidationService;
import com.itda.backend.service.validation.ValidationWorker;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * 검증에서 막힌 일지를 수정 요청 큐에서 고쳐 다시 검증받는 흐름을 한 번에 돌린다 (#357) — 업로드 → 매칭 → 검증(BLOCK)
 * → 큐 → 고치기 → 다시 검증.
 *
 * <p>AI 호출만 가짜로 바꾸고 나머지(API, 워커, 서비스, 레코더, PostgreSQL)는 실제 것을 쓴다 (Gate1PipelineIntegrationTest 와
 * 같은 방식). 워커는 테스트 프로필에서 꺼져 있어 직접 만들어 한 차례씩 돌린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class BlockedEntryRevisionIntegrationTest {

    private static final String BLOCKED = "준호 어머니 연락처 010-1234-5678 로 하원 시간을 알렸다.";
    private static final String FIXED = "준호 어머니께 하원 시간을 알렸다.";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_blocked_revision_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        String storage = Files.createTempDirectory("blocked-revision-raw").toString();
        registry.add("app.raw-storage.base-dir", () -> storage);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private SharingConsentRepository sharingConsentRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;

    @Autowired private MatchingService matchingService;
    @Autowired private MatchingResultRecorder matchingResultRecorder;
    @Autowired private MatchingProperties matchingProperties;
    @Autowired private ValidationService validationService;
    @Autowired private ValidationResultRecorder validationResultRecorder;
    @Autowired private ValidationProperties validationProperties;
    @Autowired private AgentDownProperties agentDownProperties;
    @Autowired private Clock clock;

    private User teacher;
    private Child junho;
    private MatchingWorker matchingWorker;
    private ValidationWorker validationWorker;
    /** 가짜 검증 AI 가 받은 본문 */
    private final List<String> validatedContents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        teacher = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.organizationUser(
                "revision-" + UUID.randomUUID(), organization.getId())));
        junho = childRepository.save(Child.of("김준호", LocalDate.of(2020, 3, 1)));
        childOrganizationRepository.save(ChildOrganization.of(junho.getId(), organization.getId()));
        User guardian = userRepository.save(UserFixture.parent("parent-" + UUID.randomUUID()));
        sharingConsentRepository.save(SharingConsent.grant(junho.getId(), guardian.getId(), organization.getId(),
                List.of(ConsentField.DAILY_SUMMARY)));

        matchingWorker = new MatchingWorker(matchingService, fakeMatchingAgent(), matchingResultRecorder,
                matchingProperties, agentDownProperties, clock, new AiAvailability(clock));
        validationWorker = new ValidationWorker(validationService, fakeValidationAgent(), validationResultRecorder,
                validationProperties, agentDownProperties, clock, new AiAvailability(clock));
    }

    @Test
    void 막힌_일지를_고치면_고친_본문으로_다시_검증받아_통과한다() throws Exception {
        String cardId = uploadUntilBlocked();
        Long entryId = entryIdOf(BLOCKED);

        // 다시 올리기는 없어졌고, 안 고친 본문은 받지 않는다
        resolve(cardId, "reupload").andExpect(status().isBadRequest());
        revise(cardId, BLOCKED)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_RESULT_CONTENT_UNCHANGED"));

        revise(cardId, FIXED).andExpect(status().isOk());

        JournalEntry revised = journalEntryRepository.findById(entryId).orElseThrow();
        assertThat(revised.getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
        assertThat(revised.getContent()).isEqualTo(FIXED);
        assertThat(revised.getChildId()).isEqualTo(junho.getId());
        assertThat(revised.getRevisedBy()).isEqualTo(String.valueOf(teacher.getId()));
        blockedQueue().andExpect(jsonPath("$.data", hasSize(0)));

        validationWorker.run();

        assertThat(validatedContents).containsExactly(BLOCKED, FIXED);
        assertThat(journalEntryRepository.findById(entryId).orElseThrow().getStatus())
                .isEqualTo(JournalEntryStatus.VALIDATED);
    }

    @Test
    void 고쳤는데_또_막히면_새_카드로_다시_뜨고_보류할_수_있다() throws Exception {
        String firstCard = uploadUntilBlocked();
        Long entryId = entryIdOf(BLOCKED);

        revise(firstCard, BLOCKED + " 다시 확인함.").andExpect(status().isOk());
        validationWorker.run();

        String listed = blockedQueue()
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].record.fullContent").value(BLOCKED + " 다시 확인함."))
                .andReturn().getResponse().getContentAsString();
        String secondCard = JsonPath.read(listed, "$.data[0].id");
        assertThat(secondCard).isNotEqualTo(firstCard);

        // 지난 카드로는 처리하지 않는다
        resolve(firstCard, "hold")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_RESULT_OUTDATED"));

        resolve(secondCard, "hold").andExpect(status().isOk());
        assertThat(journalEntryRepository.findById(entryId).orElseThrow().getStatus())
                .isEqualTo(JournalEntryStatus.VALIDATION_HELD);
        blockedQueue().andExpect(jsonPath("$.data", hasSize(0)));
    }

    // ── 흐름 ──

    /** 연락처가 든 일지를 올려 매칭·검증을 돌리고, 수정 요청 큐에 뜬 카드 id 를 돌려준다. */
    private String uploadUntilBlocked() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "journal.txt", "text/plain",
                ("2026-10-08 " + BLOCKED + "\n").getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(withAuth(multipart("/api/v1/raw-records").file(file)))
                .andExpect(status().isCreated());
        matchingWorker.run();
        validationWorker.run();

        String listed = blockedQueue()
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].record.fullContent").value(BLOCKED))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(listed, "$.data[0].id");
    }

    private ResultActions blockedQueue() throws Exception {
        return mockMvc.perform(withAuth(get("/api/v1/validation-results")).param("status", "BLOCK"))
                .andExpect(status().isOk());
    }

    private ResultActions revise(String cardId, String content) throws Exception {
        return mockMvc.perform(withAuth(post("/api/v1/validation-results/" + cardId + "/revise"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", content))));
    }

    private ResultActions resolve(String cardId, String action) throws Exception {
        return mockMvc.perform(withAuth(post("/api/v1/validation-results/" + cardId + "/resolve"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("action", action))));
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

    // ── 가짜 AI ──

    /** 명부에 준호가 있으면 준호로 자동 확정한다. */
    private MatchingAgentClient fakeMatchingAgent() {
        MatchingAgentClient client = mock(MatchingAgentClient.class);
        given(client.isAvailable()).willReturn(true);
        given(client.match(any())).willAnswer(invocation -> {
            MatchingAgentRequest request = invocation.getArgument(0);
            ObjectNode json = objectMapper.createObjectNode()
                    .put("journal_entry_id", request.journalEntryId())
                    .put("status", "auto")
                    .put("matched_child_id", junho.getId())
                    .put("confidence", 0.98).put("hint_mismatch", false).put("llm_called", true);
            json.putArray("evidence");
            json.putArray("mentioned_child_ids");
            json.putArray("candidates");
            return new MatchingAgentReply(objectMapper.treeToValue(json, MatchingAgentResponse.class), json.toString());
        });
        return client;
    }

    /** 전화번호(010-)가 있으면 개인정보로 BLOCK, 아니면 PASS. 받은 본문을 남긴다. */
    private ValidationAgentClient fakeValidationAgent() {
        ValidationAgentClient client = mock(ValidationAgentClient.class);
        given(client.isAvailable()).willReturn(true);
        given(client.validate(any())).willAnswer(invocation -> {
            ValidationAgentRequest request = invocation.getArgument(0);
            validatedContents.add(request.content());
            boolean block = request.content().contains("010-");
            ObjectNode json = objectMapper.createObjectNode()
                    .put("journal_entry_id", request.journalEntryId())
                    .put("verdict", block ? "BLOCK" : "PASS");
            ArrayNode types = json.putArray("issue_types");
            ArrayNode evidence = json.putArray("evidence");
            if (block) {
                types.add("개인정보표현");
                int start = request.content().indexOf("010-");
                evidence.addObject().put("start", start).put("end", start + 13);
            }
            return new ValidationAgentReply(objectMapper.treeToValue(json, ValidationAgentResponse.class),
                    json.toString());
        });
        return client;
    }
}
