package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.ApprovalReviewType;
import com.itda.backend.domain.approval.ApprovalTargetType;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.request.insight.InsightAgentRequest;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;

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
    @Autowired
    private SharingConsentRepository sharingConsentRepository;

    private InsightService insightService;
    private Child junho;
    private Organization school;
    private Organization center;

    @BeforeEach
    void setUp() {
        insightService = new InsightService(childContextRepository, summaryResultRepository, approvalRepository,
                childOrganizationRepository, organizationRepository, childRepository, sharingConsentRepository,
                new ObjectMapper());
        junho = em.persist(Child.of("김준호", LocalDate.of(2019, 3, 1)));
        school = em.persist(Organization.of("햇살초등학교", OrganizationType.SCHOOL, "1234567890"));
        center = em.persist(Organization.of("햇살아동발달센터", OrganizationType.CENTER, "1234567891"));
        em.persist(ChildOrganization.of(junho.getId(), school.getId()));
        em.persist(ChildOrganization.of(junho.getId(), center.getId()));
        // 따로 말하지 않는 테스트는 보호자가 두 기관 모두에 공유를 허락한 상태다.
        consent(school);
        consent(center);
    }

    private SharingConsent consent(Organization institution) {
        return em.persist(SharingConsent.grant(junho.getId(), 100L, institution.getId(), List.of(ConsentField.DAILY_SUMMARY)));
    }

    private void revokeConsentsTo(Organization institution) {
        em.getEntityManager()
                .createQuery("select c from SharingConsent c where c.childId = :childId and c.organizationId = :organizationId",
                        SharingConsent.class)
                .setParameter("childId", junho.getId())
                .setParameter("organizationId", institution.getId())
                .getResultList()
                .forEach(c -> c.revoke(100L, ConsentRevokeReason.GUARDIAN_REVOKED, LocalDateTime.of(2026, 10, 8, 9, 0)));
        em.flush();
    }

    /** Gate 1 을 통과한 요약 한 건. child_context 에 행이 있다는 것이 곧 승인이다. */
    private SummaryResult approved(Organization institution, LocalDate entryDate, String claims) {
        SummaryResult summary = em.persistAndFlush(SummaryResult.of(junho.getId(), entryDate, institution.getId(),
                1, "요약 본문", claims, "[1041]", "[]", "{}"));
        em.persistAndFlush(ChildContext.of(junho.getId(), summary.getId(), "승인된 본문"));
        return summary;
    }

    /** 교사가 고쳐서 승인한 요약. {@code finalContent} 가 교사가 남긴 최종본이다. */
    private SummaryResult correctedApproval(Organization institution, LocalDate entryDate, String claims,
            String finalContent) {
        SummaryResult summary = em.persistAndFlush(SummaryResult.of(junho.getId(), entryDate,
                institution.getId(), 1, "요약 본문", claims, "[1041]", "[]", "{}"));
        em.persistAndFlush(ChildContext.of(junho.getId(), summary.getId(), finalContent));
        em.persistAndFlush(new Approval(ApprovalTargetType.SUMMARY, summary.getId(),
                ApprovalReviewType.GATE1, 1L, ApprovalDecision.CORRECTED, "일부 문장을 뺐습니다"));
        return summary;
    }

    private Optional<PreparedInsight> prepare() {
        em.flush();
        em.clear();
        return insightService.prepare(junho.getId(), FROM, TO);
    }

    @Test
    void 승인된_요약의_문장을_claim으로_편다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow().request();

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

        InsightAgentRequest request = prepare().orElseThrow().request();

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

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::claimId)
                .containsExactly(summary.getId() + "-1");
    }

    /** 이 문자열이 프롬프트에 그대로 들어간다 (AI/insight/config.py INSTITUTION_TYPES). */
    @Test
    void 기관_종류는_한국어로_보낸다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        approved(center, LocalDate.of(2026, 10, 3), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow().request();

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

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::claimId)
                .containsExactly(earlier.getId() + "-0", earlier.getId() + "-1",
                        later.getId() + "-0", later.getId() + "-1");
    }

    /** 같은 날 두 기관에 기록이 있는 건 흔하다 — 기관 간 비교 인사이트가 바로 그 경우다. */
    @Test
    void 같은_날짜_요약은_id_순으로_세운다() {
        SummaryResult first = approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        SummaryResult second = approved(center, LocalDate.of(2026, 10, 2), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::claimId)
                .containsExactly(first.getId() + "-0", first.getId() + "-1",
                        second.getId() + "-0", second.getId() + "-1");
    }

    /**
     * 멘토 리뷰(#191 A4) — 고쳐 승인한 요약도 인사이트에 넣는다. 다만 교사가 <b>지운</b> 문장은
     * claims 에 근거째 남아 있으므로, 최종본에 그대로 남은 것만 보낸다 (§10.1 #7).
     */
    @Test
    void 고쳐_승인한_요약은_최종본에_남은_문장만_보낸다() {
        SummaryResult corrected = correctedApproval(school, LocalDate.of(2026, 10, 2), CLAIMS,
                "점심 줄에서 차례를 기다렸다.");

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::text)
                .containsExactly("점심 줄에서 차례를 기다렸다.");
        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::claimId)
                .containsExactly(corrected.getId() + "-0");
    }

    /** 교사가 지운 문장이 되살아나면 안 된다 — 이 작업의 핵심 안전 조건이다. */
    @Test
    void 교사가_지운_문장은_보내지_않는다() {
        correctedApproval(school, LocalDate.of(2026, 10, 2), CLAIMS, "블록을 혼자 다섯 층 쌓았다.");

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::text)
                .doesNotContain("점심 줄에서 차례를 기다렸다.");
    }

    /**
     * 줄바꿈 표기만 다른 것은 고친 것이 아니다 — Gate 1 의 판정 기준과 같아야 한다.
     *
     * <p>두 기록을 잇는 문장은 길어서 줄바꿈을 품는다 (§8.2 의 claims 예시가 그렇다). 그 문장이
     * 최종본에는 CRLF 로, claims 에는 LF 로 들어 있으면 글자 그대로 비교할 때 어긋나서,
     * 교사가 손대지 않았는데도 claim 이 날아간다.
     */
    @Test
    void 줄바꿈_표기만_다르면_그대로_남은_것으로_본다() {
        correctedApproval(school, LocalDate.of(2026, 10, 2), """
                [{"text": "학교에서는 혼자 쌓았고\\n센터에서는 도움이 필요했다.",
                  "evidence": [{"journal_entry_id": 1041, "quote": "혼자 다섯 층까지 쌓음"}]}]
                """,
                // 교사는 문장을 건드리지 않았고, 최종본만 CRLF 로 저장됐다
                "학교에서는 혼자 쌓았고\r\n센터에서는 도움이 필요했다.");

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).hasSize(1);
    }

    /**
     * 앞뒤 공백·줄바꿈도 고친 것이 아니다 — 입력창을 거치면 생긴다. Gate 1 이 {@code strip()} 을 두는 이유다.
     */
    @Test
    void 앞뒤_공백만_붙어도_그대로_남은_것으로_본다() {
        correctedApproval(school, LocalDate.of(2026, 10, 2), """
                [{"text": "점심 줄에서 차례를 기다렸다.",
                  "evidence": [{"journal_entry_id": 1041, "quote": "줄에서 차례를 기다림"}]}]
                """,
                "\n  점심 줄에서 차례를 기다렸다.  \n");

        assertThat(prepare().orElseThrow().request().claims()).hasSize(1);
    }

    /**
     * ⚠️ <b>알고 남겨 둔 구멍</b> — 부분 문자열 대조라, 지운 문장이 남은 문장 안에 들어 있으면 살아남는다.
     * 같은 날 같은 아이를 쓴 문장들이라 실제로 일어난다. 2단계(최종본 기준 claim 재생성)가 고칠 자리이고,
     * 이 테스트는 "지금은 이렇게 동작한다" 를 드러내 두려고 둔다.
     */
    @Test
    void 지운_문장이_남은_문장에_들어_있으면_아직_걸러지지_않는다() {
        correctedApproval(school, LocalDate.of(2026, 10, 2), """
                [{"text": "혼자 쌓았다.",
                  "evidence": [{"journal_entry_id": 1041, "quote": "혼자 쌓음"}]},
                 {"text": "블록을 혼자 쌓았다.",
                  "evidence": [{"journal_entry_id": 1041, "quote": "블록을 혼자 쌓음"}]}]
                """,
                // 교사는 첫 문장을 지우고 둘째만 남겼다
                "블록을 혼자 쌓았다.");

        assertThat(prepare().orElseThrow().request().claims())
                .extracting(InsightAgentRequest.Claim::text)
                .containsExactly("혼자 쌓았다.", "블록을 혼자 쌓았다.");
    }

    /**
     * Gate 1 이 "고쳤는가" 를 판정하는 기준과 <b>같아야 한다</b>. 한쪽만 바뀌면 교사가 손대지 않은
     * 문장이 인사이트에서 빠진다 — 두 곳에 같은 함수가 있어서 조용히 어긋날 수 있다.
     *
     * <p>{@code InstitutionTypeNames} 가 AI 계약 값과 맞는지 보는 테스트와 같은 자리다.
     */
    @Test
    void 고침_판정_기준이_Gate_1과_같다() {
        String raw = "\r\n  학교에서는 혼자 쌓았고\r\n센터에서는 도움이 필요했다.  \r\n";

        // Gate 1 이 "안 고쳤다" 로 보는 입력은 인사이트도 "그대로 남았다" 로 봐야 한다
        correctedApproval(school, LocalDate.of(2026, 10, 2), """
                [{"text": "학교에서는 혼자 쌓았고\\n센터에서는 도움이 필요했다.",
                  "evidence": [{"journal_entry_id": 1041, "quote": "혼자 다섯 층까지 쌓음"}]}]
                """, raw);

        assertThat(prepare().orElseThrow().request().claims()).hasSize(1);
    }

    /** 고친 요약의 문장이 하나도 안 남으면 그 요약은 재료에서 빠진다. */
    @Test
    void 남은_문장이_없으면_그_요약은_빠진다() {
        correctedApproval(school, LocalDate.of(2026, 10, 2), CLAIMS, "교사가 처음부터 새로 쓴 글.");
        approved(center, LocalDate.of(2026, 10, 3), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::institutionId)
                .containsOnly(center.getId());
    }

    /** 그냥 승인(APPROVED)은 본문이 AI 원문 그대로라 claims 와 어긋나지 않는다. */
    @Test
    void 고치지_않고_승인한_요약은_그대로_쓴다() {
        SummaryResult summary = approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        em.persistAndFlush(new Approval(ApprovalTargetType.SUMMARY, summary.getId(),
                ApprovalReviewType.GATE1, 1L, ApprovalDecision.APPROVED, null));

        assertThat(prepare().orElseThrow().request().claims()).hasSize(2);
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

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::text)
                .containsExactly("근거가 붙은 문장");
    }

    @Test
    void 기간_밖_요약은_재료에서_뺀다() {
        approved(school, LocalDate.of(2026, 9, 30), CLAIMS);
        approved(school, LocalDate.of(2026, 10, 8), CLAIMS);
        approved(center, LocalDate.of(2026, 10, 1), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow().request();

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

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::institutionId)
                .containsOnly(center.getId());
    }

    /** 동의 목록은 sharing_consent 가 ACTIVE 인 기관이다. 다니기만 하고 허락하지 않은 기관은 빠진다. */
    @Test
    void 동의하지_않은_기관은_동의_목록과_재료에서_뺀다() {
        em.getEntityManager().createQuery("delete from SharingConsent c where c.organizationId = :organizationId")
                .setParameter("organizationId", center.getId())
                .executeUpdate();
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        approved(center, LocalDate.of(2026, 10, 3), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.consentedInstitutions())
                .extracting(InsightAgentRequest.Institution::institutionId)
                .containsExactly(school.getId());
        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::institutionId)
                .containsOnly(school.getId());
    }

    /** 철회된 동의는 동의가 아니다. 철회 전에 승인된 요약도 재료로 쓰지 않는다. */
    @Test
    void 동의를_철회한_기관의_요약은_재료에서_뺀다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        approved(center, LocalDate.of(2026, 10, 3), CLAIMS);
        revokeConsentsTo(center);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.consentedInstitutions())
                .extracting(InsightAgentRequest.Institution::institutionId)
                .containsExactly(school.getId());
        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::institutionId)
                .containsOnly(school.getId());
    }

    @Test
    void 모든_동의가_철회되면_부르지_않는다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        revokeConsentsTo(school);
        revokeConsentsTo(center);

        assertThat(prepare()).isEmpty();
    }

    /**
     * 명부에서 빠질 때 동의를 끄는 경로가 아직 앱에 없다. 동의가 남아 있어도 더 이상 다니지 않는 기관은 뺀다.
     */
    @Test
    void 연결이_끊긴_기관은_동의_목록에서_뺀다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        ChildOrganization link = childOrganizationRepository
                .findByChildIdAndOrganizationId(junho.getId(), center.getId()).orElseThrow();
        link.delete();
        em.persistAndFlush(link);

        InsightAgentRequest request = prepare().orElseThrow().request();

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

        InsightAgentRequest request = prepare().orElseThrow().request();

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
