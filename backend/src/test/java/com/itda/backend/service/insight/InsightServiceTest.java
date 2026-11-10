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
import com.itda.backend.service.consent.ConsentStateReader;

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
                childOrganizationRepository, organizationRepository, childRepository,
                new ConsentStateReader(sharingConsentRepository));
        junho = em.persist(Child.of("김준호", LocalDate.of(2019, 3, 1)));
        school = em.persist(Organization.of("햇살초등학교", OrganizationType.SCHOOL, "1234567890"));
        center = em.persist(Organization.of("햇살아동발달센터", OrganizationType.CENTER, "1234567891"));
        em.persist(ChildOrganization.of(junho.getId(), school.getId()));
        em.persist(ChildOrganization.of(junho.getId(), center.getId()));
        consent(school);
        consent(center);
    }

    @Test
    void 하루_요약만_허락한_기관은_동의_목록과_재료에서_뺀다() {
        Organization academy = em.persist(Organization.of("하루요약만센터", OrganizationType.CENTER, "1000000099"));
        em.persist(ChildOrganization.of(junho.getId(), academy.getId()));
        em.persist(SharingConsent.grant(junho.getId(), 100L, academy.getId(), List.of(ConsentField.DAILY_SUMMARY)));
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        approved(academy, LocalDate.of(2026, 10, 3), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.consentedInstitutions()).extracting(InsightAgentRequest.Institution::institutionId)
                .doesNotContain(academy.getId());
        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::institutionId)
                .doesNotContain(academy.getId());
    }

    private SharingConsent consent(Organization institution) {
        return em.persist(SharingConsent.grant(junho.getId(), 100L, institution.getId(), List.of(ConsentField.DAILY_SUMMARY, ConsentField.WEEKLY_INSIGHT)));
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

    private SummaryResult approved(Organization institution, LocalDate entryDate, String claims) {
        SummaryResult summary = em.persistAndFlush(SummaryResult.of(junho.getId(), entryDate, institution.getId(),
                1, "요약 본문", claims, "[1041]", "[]", "{}"));
        em.persistAndFlush(ChildContext.of(junho.getId(), summary.getId(), "승인된 본문"));
        return summary;
    }

    private void correct(SummaryResult summary) {
        em.persistAndFlush(new Approval(ApprovalTargetType.SUMMARY, summary.getId(),
                ApprovalReviewType.GATE1, 1L, ApprovalDecision.CORRECTED, "카드로 고쳐 승인"));
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

    @Test
    void 이번_기간_앞_12주치_승인_기록을_지난_기록으로_보낸다() {
        SummaryResult thisWeek = approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        SummaryResult dayBefore = approved(school, FROM.minusDays(1), CLAIMS);
        SummaryResult oldest = approved(center, FROM.minusWeeks(12), CLAIMS);
        SummaryResult tooOld = approved(center, FROM.minusWeeks(12).minusDays(1), CLAIMS);
        SummaryResult notApproved = em.persistAndFlush(SummaryResult.of(junho.getId(), FROM.minusDays(2),
                school.getId(), 1, "요약 본문", CLAIMS, "[1041]", "[]", "{}"));

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.historyClaims()).extracting(InsightAgentRequest.Claim::claimId)
                .containsExactly(oldest.getId() + "-0", oldest.getId() + "-1",
                        dayBefore.getId() + "-0", dayBefore.getId() + "-1")
                .doesNotContain(thisWeek.getId() + "-0", tooOld.getId() + "-0", notApproved.getId() + "-0");
        assertThat(request.historyClaims().get(0).institutionType()).isEqualTo("센터");
    }

    @Test
    void 동의가_꺼진_기관의_지난_기록은_보내지_않는다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        SummaryResult revoked = approved(center, FROM.minusWeeks(1), CLAIMS);
        revokeConsentsTo(center);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.historyClaims()).extracting(InsightAgentRequest.Claim::claimId)
                .doesNotContain(revoked.getId() + "-0", revoked.getId() + "-1");
    }

    @Test
    void 지난_기록이_200개를_넘으면_오래된_요약부터_뺀다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        SummaryResult oldest = approved(center, FROM.minusWeeks(3), CLAIMS);
        SummaryResult middle = approved(school, FROM.minusWeeks(2), claims(InsightService.HISTORY_LIMIT - 2));
        SummaryResult newest = approved(center, FROM.minusWeeks(1), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.historyClaims()).hasSize(InsightService.HISTORY_LIMIT);
        assertThat(request.historyClaims()).extracting(InsightAgentRequest.Claim::claimId)
                .doesNotContain(oldest.getId() + "-0", oldest.getId() + "-1")
                .contains(middle.getId() + "-0", newest.getId() + "-1");
    }

    @Test
    void 지난_기록을_자를_때_요약을_쪼개지_않는다() {
        approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        approved(center, FROM.minusWeeks(3), CLAIMS);
        SummaryResult middle = approved(school, FROM.minusWeeks(2), claims(InsightService.HISTORY_LIMIT - 1));
        SummaryResult newest = approved(center, FROM.minusWeeks(1), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.historyClaims()).extracting(InsightAgentRequest.Claim::claimId)
                .containsExactly(newest.getId() + "-0", newest.getId() + "-1")
                .doesNotContain(middle.getId() + "-1");
    }

    private static String claims(int count) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            json.append(i == 0 ? "" : ",").append("""
                    {"text": "문장 %d", "evidence": [{"journal_entry_id": 1041, "quote": "인용"}]}""".formatted(i));
        }
        return json.append("]").toString();
    }

    @Test
    void claim_id는_요약_id와_배열_순서로_만든다() {
        SummaryResult summary = approved(school, LocalDate.of(2026, 10, 2), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::claimId)
                .containsExactly(summary.getId() + "-0", summary.getId() + "-1");
    }

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

    @Test
    void 승인순이_아니라_관찰_날짜순으로_보낸다() {
        SummaryResult later = approved(school, LocalDate.of(2026, 10, 6), CLAIMS);
        SummaryResult earlier = approved(center, LocalDate.of(2026, 10, 2), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::claimId)
                .containsExactly(earlier.getId() + "-0", earlier.getId() + "-1",
                        later.getId() + "-0", later.getId() + "-1");
    }

    @Test
    void 같은_날짜_요약은_id_순으로_세운다() {
        SummaryResult first = approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        SummaryResult second = approved(center, LocalDate.of(2026, 10, 2), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::claimId)
                .containsExactly(first.getId() + "-0", first.getId() + "-1",
                        second.getId() + "-0", second.getId() + "-1");
    }

    @Test
    void 카드로_고쳐_승인한_요약도_재료로_쓴다() {
        SummaryResult corrected = approved(school, LocalDate.of(2026, 10, 2), """
                [{"text": "점심 줄에서 차례를 기다렸다.", "source": "AI",
                  "evidence": [{"journal_entry_id": 1041, "quote": "줄에서 차례를 기다림"}]},
                 {"text": "손을 잡고 세어 주자 진정했다.", "source": "EDITED",
                  "evidence": [{"journal_entry_id": 1042, "quote": "손 잡고 셈"}]},
                 {"text": "약 먹은 날 차분했다.", "source": "TEACHER",
                  "evidence": [{"journal_entry_id": null, "quote": "약 먹은 날 차분했다."}]}]
                """);
        correct(corrected);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::source).containsExactly(
                InsightAgentRequest.ClaimSource.AI, InsightAgentRequest.ClaimSource.EDITED,
                InsightAgentRequest.ClaimSource.TEACHER);
    }

    @Test
    void 옛_방식으로_고쳐_승인한_요약은_계속_뺀다() {
        SummaryResult legacy = approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        approved(center, LocalDate.of(2026, 10, 3), CLAIMS);
        correct(legacy);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::institutionId)
                .containsOnly(center.getId());
    }

    @Test
    void 고치지_않고_승인한_요약은_그대로_쓴다() {
        SummaryResult summary = approved(school, LocalDate.of(2026, 10, 2), CLAIMS);
        em.persistAndFlush(new Approval(ApprovalTargetType.SUMMARY, summary.getId(),
                ApprovalReviewType.GATE1, 1L, ApprovalDecision.APPROVED, null));

        assertThat(prepare().orElseThrow().request().claims()).hasSize(2);
    }

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
    void source_가_없으면_AI_로_보낸다() {
        approved(school, LocalDate.of(2026, 10, 2), """
                [{"text": "옛 요약 문장", "evidence": [{"journal_entry_id": 1041, "quote": "원문 인용"}]}]
                """);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::source)
                .containsExactly(InsightAgentRequest.ClaimSource.AI);
    }

    @Test
    void source_가_null_이어도_AI_로_보낸다() {
        approved(school, LocalDate.of(2026, 10, 2), """
                [{"text": "고치지 않은 문장", "source": null,
                  "evidence": [{"journal_entry_id": 1041, "quote": "원문 인용"}]}]
                """);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::source)
                .containsExactly(InsightAgentRequest.ClaimSource.AI);
    }

    @Test
    void 인용이_비면_그_근거를_버린다() {
        approved(school, LocalDate.of(2026, 10, 2), """
                [{"text": "인용이 빈 근거만 달린 문장",
                  "evidence": [{"journal_entry_id": 1041, "quote": ""},
                               {"journal_entry_id": 1042}]},
                 {"text": "멀쩡한 문장",
                  "evidence": [{"journal_entry_id": 1041, "quote": "원문 인용"}]}]
                """);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::text)
                .containsExactly("멀쩡한 문장");
    }

    @Test
    void 교사가_새로_쓴_문장은_일지_번호_없이_보낸다() {
        approved(school, LocalDate.of(2026, 10, 2), """
                [{"text": "약 먹은 날 차분했다.", "source": "TEACHER",
                  "evidence": [{"journal_entry_id": null, "quote": "약 먹은 날 차분했다."}]}]
                """);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.source()).isEqualTo(InsightAgentRequest.ClaimSource.TEACHER);
            assertThat(claim.evidence()).singleElement().satisfies(evidence -> {
                assertThat(evidence.journalEntryId()).isNull();
                assertThat(evidence.quote()).isEqualTo("약 먹은 날 차분했다.");
            });
        });
    }

    @Test
    void 교사가_고친_문장은_일지_번호가_있어야_간다() {
        approved(school, LocalDate.of(2026, 10, 2), """
                [{"text": "고쳤는데 번호가 빠진 문장", "source": "EDITED",
                  "evidence": [{"journal_entry_id": null, "quote": "원문 인용"}]},
                 {"text": "고치고 번호도 있는 문장", "source": "EDITED",
                  "evidence": [{"journal_entry_id": 1041, "quote": "원문 인용"}]}]
                """);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::text)
                .containsExactly("고치고 번호도 있는 문장");
    }

    @Test
    void 모르는_source_는_그_문장을_빼고_보낸다() {
        approved(school, LocalDate.of(2026, 10, 2), """
                [{"text": "모르는 출처", "source": "ROBOT",
                  "evidence": [{"journal_entry_id": 1041, "quote": "원문 인용"}]},
                 {"text": "멀쩡한 문장", "evidence": [{"journal_entry_id": 1042, "quote": "원문 인용"}]}]
                """);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::text)
                .containsExactly("멀쩡한 문장");
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

    @Test
    void claims가_깨진_요약만_빼고_나머지로_간다() {
        approved(school, LocalDate.of(2026, 10, 2), "{깨진 JSON");
        approved(center, LocalDate.of(2026, 10, 3), CLAIMS);

        InsightAgentRequest request = prepare().orElseThrow().request();

        assertThat(request.claims()).extracting(InsightAgentRequest.Claim::institutionId)
                .containsOnly(center.getId());
    }

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
