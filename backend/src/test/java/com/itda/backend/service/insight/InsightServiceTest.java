package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.Approval;
import com.itda.backend.domain.ApprovalDecision;
import com.itda.backend.domain.ApprovalReviewType;
import com.itda.backend.domain.ApprovalTargetType;
import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildContext;
import com.itda.backend.domain.ChildOrganization;
import com.itda.backend.domain.Organization;
import com.itda.backend.domain.OrganizationType;
import com.itda.backend.domain.SummaryResult;
import com.itda.backend.dto.request.InsightAgentRequest;
import com.itda.backend.repository.ApprovalRepository;
import com.itda.backend.repository.ChildContextRepository;
import com.itda.backend.repository.ChildOrganizationRepository;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.SummaryResultRepository;

/** 인사이트 재료 조립 — 승인된 요약의 claim 을 모아 에이전트 입력으로 편다. */
@DataJpaTest
@ActiveProfiles("test")
class InsightServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 7);

    private static final String CLAIMS = """
            [{"text": "점심 줄에서 차례를 기다렸다.",
              "evidence": [{"journal_entry_id": 1041, "quote": "줄에서 차례를 기다림",
                            "span": {"start": 0, "end": 10}}]},
             {"text": "블록을 혼자 다섯 층 쌓았다.",
              "evidence": [{"journal_entry_id": 1041, "quote": "혼자 다섯 층까지 쌓음",
                            "span": {"start": 12, "end": 24}}]}]
            """;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ChildContextRepository childContextRepository;
    @Autowired
    private SummaryResultRepository summaryResultRepository;
    @Autowired
    private ApprovalRepository approvalRepository;
    @Autowired
    private ChildOrganizationRepository childOrganizationRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private ChildRepository childRepository;

    private InsightService insightService;
    private Child junho;
    private Organization school;
    private Organization center;

    @BeforeEach
    void setUp() {
        insightService = new InsightService(childContextRepository, summaryResultRepository, approvalRepository,
                childOrganizationRepository, organizationRepository, childRepository, new ObjectMapper());
        junho = em.persist(Child.of("김준호", LocalDate.of(2019, 3, 1)));
        school = em.persist(Organization.of("햇살초등학교", OrganizationType.SCHOOL, "1234567890"));
        center = em.persist(Organization.of("햇살아동발달센터", OrganizationType.CENTER, "1234567891"));
        em.persist(ChildOrganization.of(junho.getId(), school.getId()));
        em.persist(ChildOrganization.of(junho.getId(), center.getId()));
    }

    /** Gate 1 을 통과한 요약 한 건. child_context 에 행이 있다는 것이 곧 승인이다. */
    private SummaryResult approved(Organization institution, LocalDate entryDate, String claims) {
        SummaryResult summary = em.persistAndFlush(SummaryResult.of(junho.getId(), entryDate, institution.getId(),
                1, "요약 본문", claims, "[1041]", "[]", "{}"));
        em.persistAndFlush(ChildContext.of(junho.getId(), summary.getId(), "승인된 본문"));
        return summary;
    }

    private Optional<InsightAgentRequest> prepare() {
        em.flush();
        em.clear();
        return insightService.prepare(junho.getId(), FROM, TO);
    }

    @Test
    void 승인된_요약의_문장을_claim으로_편다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow();

        assertThat(request.childId()).isEqualTo(junho.getId());
        assertThat(request.childName()).isEqualTo("김준호");
        assertThat(request.periodFrom()).isEqualTo("2026-10-01");
        assertThat(request.periodTo()).isEqualTo("2026-10-07");
        assertThat(request.claims()).hasSize(2);

        InsightAgentRequest.Claim first = request.claims().get(0);
        assertThat(first.text()).isEqualTo("점심 줄에서 차례를 기다렸다.");
        assertThat(first.entryDate()).isEqualTo("2026-10-02");
        assertThat(first.institutionId()).isEqualTo(school.getId());
        assertThat(first.evidence()).hasSize(1);
        assertThat(first.evidence().get(0).journalEntryId()).isEqualTo(1041L);
        assertThat(first.evidence().get(0).quote()).isEqualTo("줄에서 차례를 기다림");
    }

    /** claim_id 는 요약에 없는 값이라 BE 가 붙인다. 뒷자리는 claims 배열 순서다. */
    @Test
    void claim_id는_요약_id와_배열_순서로_만든다() {
        SummaryResult summary = approved(school, LocalDate.of(2026, 10, 2), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::claimId)
                .containsExactly(summary.getId() + "-0", summary.getId() + "-1");
    }

    /**
     * 뒷자리는 <b>원본 배열</b> 위치여야 한다. 거른 뒤 위치를 쓰면 인사이트가 가리키는 근거가 한 칸씩
     * 밀려, 다른 문장의 근거를 자기 근거라고 내보낸다. 기관 경계를 넘는 글이라 조용히 틀리면 위험하다.
     */
    @Test
    void 버려진_문장이_앞에_있어도_claim_id는_원본_위치를_쓴다() {
        SummaryResult summary = approved(school, LocalDate.of(2026, 10, 2), """
                [{"text": "근거가 없어 버려질 문장", "evidence": []},
                 {"text": "살아남는 문장", "evidence": [{"journal_entry_id": 1041, "quote": "원문 인용"}]}]
                """);

        InsightAgentRequest request = prepare().orElseThrow();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::claimId)
                .containsExactly(summary.getId() + "-1");
    }

    /** 이 문자열이 프롬프트에 그대로 들어간다 (AI/insight/config.py INSTITUTION_TYPES). */
    @Test
    void 기관_종류는_한국어로_보낸다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        approved(center, LocalDate.of(2026, 10, 3), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::institutionType)
                .containsExactly("학교", "학교", "센터", "센터");
        assertThat(request.consentedInstitutions())
                .extracting(InsightAgentRequest.Institution::institutionType)
                .containsExactlyInAnyOrder("학교", "센터");
    }

    /**
     * child_context 는 승인순이라 8월 기록을 10월에 승인하면 맨 앞에 온다.
     * 인사이트는 기간의 흐름을 보므로 관찰 날짜로 다시 세워야 한다.
     */
    @Test
    void 승인순이_아니라_관찰_날짜순으로_보낸다() {
        // 먼저 저장(= 먼저 승인)된 쪽이 나중 날짜다. 승인순을 그대로 쓰면 이 순서가 뒤집힌다.
        SummaryResult later = approved(school, LocalDate.of(2026, 10, 6), CLAIMS);
        SummaryResult earlier = approved(center, LocalDate.of(2026, 10, 2), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::claimId)
                .containsExactly(earlier.getId() + "-0", earlier.getId() + "-1",
                        later.getId() + "-0", later.getId() + "-1");
    }

    /** 같은 날 두 기관에 기록이 있는 건 흔하다 — 기관 간 비교 인사이트가 바로 그 경우다. */
    @Test
    void 같은_날짜_요약은_id_순으로_세운다() {
        SummaryResult first = approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        SummaryResult second = approved(center, LocalDate.of(2026, 10, 2), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::claimId)
                .containsExactly(first.getId() + "-0", first.getId() + "-1",
                        second.getId() + "-0", second.getId() + "-1");
    }

    /**
     * 교사가 고쳐서 승인한 요약은 통째로 뺀다 (schema.md §10.1 #7).
     * 고친 본문은 child_context 에만 있고 claims 는 AI 원문 그대로라, 교사가 지운 문장이
     * 근거를 달고 그대로 다른 기관에 나간다.
     */
    @Test
    void 교사가_고쳐_승인한_요약은_재료에서_뺀다() {
        SummaryResult corrected = approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        approved(center, LocalDate.of(2026, 10, 3), CLAIMS);
        em.persistAndFlush(new Approval(ApprovalTargetType.SUMMARY, corrected.getId(),
                ApprovalReviewType.GATE1, 1L, ApprovalDecision.CORRECTED, "추정 표현을 뺐습니다"));

        InsightAgentRequest request = prepare().orElseThrow();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::institutionId)
                .containsOnly(center.getId());
    }

    /** 그냥 승인(APPROVED)은 본문이 AI 원문 그대로라 claims 와 어긋나지 않는다. */
    @Test
    void 고치지_않고_승인한_요약은_그대로_쓴다() {
        SummaryResult summary = approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        em.persistAndFlush(new Approval(ApprovalTargetType.SUMMARY, summary.getId(),
                ApprovalReviewType.GATE1, 1L, ApprovalDecision.APPROVED, null));

        assertThat(prepare().orElseThrow().claims()).hasSize(2);
    }

    /**
     * 계약이 evidence 를 최소 1건으로 막아 뒀다. 보내면 422 고, 422 는 재시도 없이 바로 실패한다.
     * 원문으로 추적되지 않는 내용이 다른 기관으로 나가서도 안 된다 (CRITERIA §2).
     */
    @Test
    void 근거가_없는_문장은_보내지_않는다() {
        approved(school, LocalDate.of(2026, 10, 2), """
                [{"text": "근거가 붙은 문장", "evidence": [{"journal_entry_id": 1041, "quote": "원문 인용"}]},
                 {"text": "근거가 비어 버린 문장", "evidence": []},
                 {"text": "근거 키 자체가 없는 문장"}]
                """);

        InsightAgentRequest request = prepare().orElseThrow();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::text)
                .containsExactly("근거가 붙은 문장");
    }

    @Test
    void 기간_밖_요약은_재료에서_뺀다() {
        approved(school, LocalDate.of(2026, 9, 30), CLAIMS);
        approved(school, LocalDate.of(2026, 10, 8), CLAIMS);
        approved(center, LocalDate.of(2026, 10, 1), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::entryDate)
                .containsOnly("2026-10-01");
    }

    /** 빈 claims 로 부르면 모델만 쓰고 결과는 어차피 비어 있다. */
    @Test
    void 승인된_요약이_없으면_부르지_않는다() {
        assertThat(prepare()).isEmpty();
    }

    @Test
    void 근거가_남은_문장이_하나도_없으면_부르지_않는다() {
        approved(school, LocalDate.of(2026, 10, 2), """
                [{"text": "근거가 전부 버려진 문장", "evidence": []}]
                """);

        assertThat(prepare()).isEmpty();
    }

    /** AI 가 쓴 값이 깨진 것 — 한 건 때문에 기간 전체를 버리지 않는다. */
    @Test
    void claims가_깨진_요약만_빼고_나머지로_간다() {
        approved(school, LocalDate.of(2026, 10, 2), "{깨진 JSON");
        approved(center, LocalDate.of(2026, 10, 3), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::institutionId)
                .containsOnly(center.getId());
    }

    /**
     * sharing_consent 가 아직 없어 아이가 등록된 기관 전부를 넣는다 (AI/insight/schemas.py).
     * 연결이 끊긴 기관은 더 이상 다니지 않으므로 뺀다.
     */
    @Test
    void 연결이_끊긴_기관은_동의_목록에서_뺀다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        ChildOrganization link = childOrganizationRepository
                .findByChildIdAndOrganizationId(junho.getId(), center.getId()).orElseThrow();
        link.delete();
        em.persistAndFlush(link);

        InsightAgentRequest request = prepare().orElseThrow();

        assertThat(request.consentedInstitutions())
                .extracting(InsightAgentRequest.Institution::institutionId)
                .containsExactly(school.getId());
    }

    /**
     * 동의 목록이 비면 아무 claim 도 쓰지 않는다 — <b>모르면 막는다</b> (CRITERIA §7).
     * 빈 목록으로 부르면 AI 가 전부 걸러 결과는 어차피 비는데, 그 사이에 원문 인용이 한 번 나간다.
     */
    @Test
    void 동의한_기관이_하나도_없으면_부르지_않는다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        for (ChildOrganization link : childOrganizationRepository
                .findByChildIdAndDeletedAtIsNull(junho.getId())) {
            link.delete();
            em.persistAndFlush(link);
        }

        assertThat(prepare()).isEmpty();
    }

    /**
     * 받는 쪽만 막으면 철회된 기관의 기록이 재료로 들어가 다른 기관으로 흘러간다 (CRITERIA §7).
     * {@code child_context} 는 지우지 않고 쌓이기만 해서, 연결을 끊어도 그 기관의 승인본은 남는다.
     */
    @Test
    void 동의_목록_밖_기관의_요약은_재료에서_뺀다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        approved(center, LocalDate.of(2026, 10, 3), CLAIMS);
        ChildOrganization link = childOrganizationRepository
                .findByChildIdAndOrganizationId(junho.getId(), center.getId()).orElseThrow();
        link.delete();
        em.persistAndFlush(link);

        InsightAgentRequest request = prepare().orElseThrow();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::institutionId)
                .containsOnly(school.getId());
    }

    /** 삭제된 아이의 이름이 AI 로 나가면 안 된다. */
    @Test
    void 삭제된_아이는_부르지_않는다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        Child found = childRepository.findById(junho.getId()).orElseThrow();
        found.delete();
        em.persistAndFlush(found);

        assertThat(prepare()).isEmpty();
    }

    @Test
    void 기관_종류_이름은_AI_계약과_같다() {
        assertThat(List.of(OrganizationType.values()))
                .allSatisfy(type -> assertThat(InstitutionTypeNames.of(type)).isNotBlank());
        assertThat(InstitutionTypeNames.of(OrganizationType.ACTIVITY_SUPPORT)).isEqualTo("활동지원사");
    }
}
