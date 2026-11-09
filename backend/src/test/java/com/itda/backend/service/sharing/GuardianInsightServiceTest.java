package com.itda.backend.service.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.response.guardian.GuardianInsightResponse;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.exception.sharing.SharingErrorCode;
import com.itda.backend.exception.sharing.SharingException;
import com.itda.backend.global.config.ClockConfig;
import com.itda.backend.service.child.GuardianJournalQueryService;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.insight.InsightEvidenceReader;
import com.itda.backend.service.insight.QuietWeekReader;
import com.itda.backend.service.insight.QuietWeekReader.QuietWeek;
import com.itda.backend.service.insight.QuietWeekReader.Reason;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.child.GuardianChildGuard;

@DataJpaTest
@ActiveProfiles("test")
@Import({GuardianInsightService.class, GuardianChildGuard.class, InsightEvidenceReader.class, GuardianJournalQueryService.class,
        SharingReadRecorder.class, ConsentStateReader.class, ClockConfig.class})
class GuardianInsightServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 17);
    private static final LocalDate TO = LocalDate.of(2026, 8, 23);
    private static final String MOTHER = "u-mother";
    private static final String STRANGER = "u-stranger";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private GuardianInsightService service;

    @Autowired
    private GuardianJournalQueryService journalService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private QuietWeekReader quietWeekReader;

    private Long guardianId;
    private Long childId;
    private Long schoolId;
    private Long centerId;
    private Long insightId;
    private Long mineId;
    private Long centerGrantId;
    private Long centerEnrollmentId;
    private Long centerConsentId;

    @BeforeEach
    void setUp() {
        childId = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2018, 3, 2))).getId();
        schoolId = em.persistAndFlush(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001")).getId();
        centerId = em.persistAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1000000002")).getId();
        guardianId = 7L;
        centerEnrollmentId = em.persistAndFlush(ChildOrganization.of(childId, centerId, "C-1")).getId();
        centerConsentId = em.persistAndFlush(
                SharingConsent.grant(childId, guardianId, centerId, List.of(ConsentField.WEEKLY_INSIGHT))).getId();

        insightId = em.persistAndFlush(InsightResult.of(childId, FROM, TO, 1, "급식실 입장 직후",
                "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.", null, "[]", "[]",
                "[%d]".formatted(schoolId), false, null)).getId();
        mineId = em.persistAndFlush(SharingHistory.grant(childId, insightId, schoolId,
                ReceiverType.GUARDIAN, guardianId)).getId();
        centerGrantId = em.persistAndFlush(SharingHistory.grant(childId, insightId, schoolId,
                ReceiverType.ORGANIZATION, centerId)).getId();

        given(userService.getParentIdOf(MOTHER)).willReturn(guardianId);
        given(userService.getParentIdOf(STRANGER)).willReturn(8L);
        em.clear();
    }

    @Test
    void 내게_열린_인사이트만_보인다() {
        assertThat(service.getInsights(MOTHER)).singleElement().satisfies(insight -> {
            assertThat(insight.id()).isEqualTo(String.valueOf(insightId));
            assertThat(insight.from().id()).isEqualTo(String.valueOf(schoolId));
        });
        assertThat(service.getInsights(STRANGER)).isEmpty();
    }

    @Test
    void 내_아이의_특이사항_없음_주가_리포트와_함께_보인다() {
        em.persistAndFlush(ChildGuardian.link(childId, guardianId, 1L));
        LocalDate quietFrom = FROM.minusWeeks(1);
        given(quietWeekReader.read(any())).willReturn(
                List.of(new QuietWeek(childId, quietFrom, quietFrom.plusDays(6), Reason.NOTHING_NOTABLE)));

        assertThat(service.getInsights(MOTHER))
                .extracting(GuardianInsightResponse::kind, GuardianInsightResponse::id)
                .containsExactly(tuple("insight", String.valueOf(insightId)),
                        tuple("quiet", "quiet-%d-%s".formatted(childId, quietFrom)));
        GuardianInsightResponse quiet = service.getInsights(MOTHER).get(1);
        assertThat(quiet.quietReason()).isEqualTo("NOTHING_NOTABLE");
        assertThat(quiet.childName()).isEqualTo("김하늘");
        assertThat(quiet.content()).isNull();
        assertThat(quiet.evidence()).isEmpty();
        assertThat(quiet.approvedAt()).isEqualTo(quietFrom.plusDays(7).atStartOfDay());
        assertThat(service.getInsights(STRANGER)).isEmpty();
    }

    @Test
    void 아이를_고르면_그_아이의_리포트와_특이사항_없음_주만_보인다() {
        Long sea = em.persistAndFlush(Child.of("이바다", LocalDate.of(2019, 5, 1))).getId();
        em.persistAndFlush(ChildGuardian.link(childId, guardianId, 1L));
        em.persistAndFlush(ChildGuardian.link(sea, guardianId, 1L));
        Long seaInsight = em.persistAndFlush(InsightResult.of(sea, FROM, TO, 1, "상황", "반응", "본문", null, "[]",
                "[]", "[%d]".formatted(schoolId), false, null)).getId();
        em.persistAndFlush(SharingHistory.grant(sea, seaInsight, schoolId, ReceiverType.GUARDIAN, guardianId));
        em.clear();
        LocalDate quietFrom = FROM.minusWeeks(1);
        given(quietWeekReader.read(any())).willAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return Stream.of(childId, sea).filter(ids::contains)
                    .map(id -> new QuietWeek(id, quietFrom, quietFrom.plusDays(6), Reason.NOTHING_NOTABLE))
                    .toList();
        });

        assertThat(service.getInsights(MOTHER, childId))
                .extracting(GuardianInsightResponse::kind, GuardianInsightResponse::id)
                .containsExactly(tuple("insight", String.valueOf(insightId)),
                        tuple("quiet", "quiet-%d-%s".formatted(childId, quietFrom)));
        assertThat(service.getInsights(MOTHER, sea))
                .extracting(GuardianInsightResponse::kind, GuardianInsightResponse::id)
                .containsExactly(tuple("insight", String.valueOf(seaInsight)),
                        tuple("quiet", "quiet-%d-%s".formatted(sea, quietFrom)));
        assertThat(service.getInsights(MOTHER)).hasSize(4);
    }

    @Test
    void 연결되지_않았거나_삭제된_아이를_고르면_403() {
        assertThatThrownBy(() -> service.getInsights(MOTHER, childId))
                .isInstanceOf(ChildException.class)
                .extracting(e -> ((ChildException) e).getErrorCode())
                .isEqualTo(ChildErrorCode.CHILD_NOT_LINKED);

        em.persistAndFlush(ChildGuardian.link(childId, guardianId, 1L));
        em.find(Child.class, childId).delete();
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.getInsights(MOTHER, childId))
                .isInstanceOf(ChildException.class)
                .extracting(e -> ((ChildException) e).getErrorCode())
                .isEqualTo(ChildErrorCode.CHILD_NOT_LINKED);
    }

    @Test
    void 열어_보면_안_읽은_아이에서_빠진다() {
        Long sea = em.persistAndFlush(Child.of("이바다", LocalDate.of(2019, 5, 1))).getId();
        em.clear();

        assertThat(service.childIdsWithUnread(guardianId, List.of(childId, sea))).containsExactly(childId);

        service.getInsight(MOTHER, insightId);

        assertThat(service.childIdsWithUnread(guardianId, List.of(childId, sea))).isEmpty();
    }

    @Test
    void 남의_권한과_거둔_권한은_안_읽음이_아니다() {
        assertThat(service.childIdsWithUnread(8L, List.of(childId))).isEmpty();

        revoke(mineId, ConsentRevokeReason.GUARDIAN_UNLINKED);

        assertThat(service.childIdsWithUnread(guardianId, List.of(childId))).isEmpty();
        assertThat(service.childIdsWithUnread(guardianId, List.of())).isEmpty();
    }

    @Test
    void 이_글을_볼_수_있는_기관을_보여준다() {
        assertThat(service.getInsights(MOTHER).get(0).sharedWith())
                .extracting(InstitutionResponse::id).containsExactly(String.valueOf(centerId));
    }

    @Test
    void 동의를_꺼서_거둔_기관은_목록에서_빠진다() {
        revoke(centerGrantId, ConsentRevokeReason.GUARDIAN_REVOKED);

        assertThat(service.getInsights(MOTHER).get(0).sharedWith()).isEmpty();
    }

    @Test
    void 명부에서_빠진_기관은_권한이_살아_있어도_함께_보는_기관에서_빠진다() {
        em.find(ChildOrganization.class, centerEnrollmentId).delete();
        em.flush();
        em.clear();

        assertThat(service.getInsights(MOTHER).get(0).sharedWith()).isEmpty();
        assertThat(service.getInsight(MOTHER, insightId).sharedWith()).isEmpty();
        assertThat(em.find(SharingHistory.class, centerGrantId).isActive()).isTrue();
    }

    @Test
    void 그_기관_동의만_꺼져도_함께_보는_기관에서_빠진다() {
        em.find(SharingConsent.class, centerConsentId)
                .revoke(guardianId, ConsentRevokeReason.GUARDIAN_REVOKED, LocalDateTime.now());
        em.flush();
        em.clear();

        assertThat(service.getInsights(MOTHER).get(0).sharedWith()).isEmpty();
    }

    @Test
    void 함께_보는_기관은_이름순이고_목록과_단건이_같다() {
        Long academyId = em.persistAndFlush(Organization.of("가나다미술학원", OrganizationType.CENTER, "1000000003")).getId();
        em.persist(ChildOrganization.of(childId, academyId, "A-1"));
        em.persist(SharingConsent.grant(childId, guardianId, academyId, List.of(ConsentField.WEEKLY_INSIGHT)));
        em.persistAndFlush(SharingHistory.grant(childId, insightId, schoolId, ReceiverType.ORGANIZATION, academyId));
        em.clear();

        List<InstitutionResponse> fromList = service.getInsights(MOTHER).get(0).sharedWith();

        assertThat(fromList).extracting(InstitutionResponse::name).containsExactly("가나다미술학원", "햇살센터");
        assertThat(service.getInsight(MOTHER, insightId).sharedWith()).isEqualTo(fromList);
    }

    @Test
    void 아이가_둘이어도_각_글의_함께_보는_기관은_자기_아이_기준이다() {
        Long otherChildId = em.persistAndFlush(Child.of("이바다", LocalDate.of(2017, 5, 1))).getId();
        Long otherInsightId = em.persistAndFlush(InsightResult.of(otherChildId, FROM, TO, 1, "상황", "반응",
                "다른 아이 글", null, "[]", "[]", "[%d]".formatted(schoolId), false, null)).getId();
        em.persist(SharingHistory.grant(otherChildId, otherInsightId, schoolId, ReceiverType.GUARDIAN, guardianId));
        em.persistAndFlush(
                SharingHistory.grant(otherChildId, otherInsightId, schoolId, ReceiverType.ORGANIZATION, centerId));
        em.clear();

        List<GuardianInsightResponse> insights = service.getInsights(MOTHER);

        assertThat(insights).hasSize(2);
        assertThat(insights).filteredOn(i -> i.id().equals(String.valueOf(insightId))).singleElement()
                .satisfies(i -> assertThat(i.sharedWith()).extracting(InstitutionResponse::id)
                        .containsExactly(String.valueOf(centerId)));
        assertThat(insights).filteredOn(i -> i.id().equals(String.valueOf(otherInsightId))).singleElement()
                .satisfies(i -> assertThat(i.sharedWith()).isEmpty());
    }

    @Test
    void 삭제된_아이의_글은_이름이_비고_함께_보는_기관도_없다() {
        em.find(Child.class, childId).delete();
        em.flush();
        em.clear();

        assertThat(service.getInsights(MOTHER)).singleElement().satisfies(insight -> {
            assertThat(insight.childName()).isNull();
            assertThat(insight.sharedWith()).isEmpty();
        });
    }

    @Test
    void 내게_열리지_않은_인사이트는_404() {
        assertThatThrownBy(() -> service.getInsight(STRANGER, insightId))
                .isInstanceOf(SharingException.class)
                .extracting(e -> ((SharingException) e).getErrorCode())
                .isEqualTo(SharingErrorCode.NOT_FOUND);
    }

    @Test
    void 내_권한이_거둬지면_더_못_본다() {
        revoke(mineId, ConsentRevokeReason.GUARDIAN_UNLINKED);

        assertThat(service.getInsights(MOTHER)).isEmpty();
        assertThatThrownBy(() -> service.getInsight(MOTHER, insightId))
                .isInstanceOf(SharingException.class);
    }

    @Test
    void 처음_열어_본_시각을_남기고_다시_열어도_덮지_않는다() {
        assertThat(service.getInsights(MOTHER).get(0).readAt()).isNull();

        service.getInsight(MOTHER, insightId);
        em.flush();
        em.clear();
        LocalDateTime first = service.getInsight(MOTHER, insightId).readAt();
        em.flush();
        em.clear();
        assertThat(first).isNotNull();

        assertThat(service.getInsight(MOTHER, insightId).readAt()).isEqualTo(first);
    }

    @Test
    void 승인_시각을_싣는다() {
        LocalDateTime approvedAt = LocalDateTime.of(2026, 10, 27, 15, 0);
        approve(insightId, approvedAt);

        assertThat(service.getInsights(MOTHER).get(0).approvedAt()).isEqualTo(approvedAt);
        assertThat(service.getInsight(MOTHER, insightId).approvedAt()).isEqualTo(approvedAt);
    }

    @Test
    void 승인_시각이_최근인_글이_위다() {
        Long later = insight("두 번째 글");
        grantToMother(later);
        approve(insightId, LocalDateTime.of(2026, 10, 28, 9, 0));
        approve(later, LocalDateTime.of(2026, 10, 27, 9, 0));

        assertThat(service.getInsights(MOTHER)).extracting(GuardianInsightResponse::id)
                .containsExactly(String.valueOf(insightId), String.valueOf(later));
    }

    @Test
    void 나중에_열린_지난_글도_승인_시각_자리에_놓인다() {
        Long lastWeek = insight("지난주 글");
        grantToMother(lastWeek);
        approve(lastWeek, LocalDateTime.of(2026, 10, 20, 9, 0));
        approve(insightId, LocalDateTime.of(2026, 10, 27, 9, 0));

        assertThat(service.getInsights(MOTHER)).extracting(GuardianInsightResponse::id)
                .containsExactly(String.valueOf(insightId), String.valueOf(lastWeek));
    }

    @Test
    void 승인_기록이_없으면_열린_시각으로_대신한다() {
        LocalDateTime grantedAt = em.find(SharingHistory.class, mineId).getGrantedAt();

        assertThat(service.getInsights(MOTHER).get(0).approvedAt()).isEqualTo(grantedAt);
    }

    @Test
    void 근거는_일지_id_날짜_기관을_관찰_날짜_오래된_순으로_준다() {
        Long school = journal(LocalDate.of(2026, 8, 20), schoolId);
        Long center = journal(LocalDate.of(2026, 8, 18), centerId);
        Long id = citing("[\"%d-0\",\"%d-0\"]".formatted(summaryOf(school), summaryOf(center)), null, null);

        assertThat(service.getInsight(MOTHER, id).evidence())
                .extracting(GuardianInsightResponse.Evidence::journalEntryId, GuardianInsightResponse.Evidence::date,
                        e -> e.institution().name(), GuardianInsightResponse.Evidence::lookedUp)
                .containsExactly(
                        tuple(String.valueOf(center), "2026-08-18", "햇살센터", false),
                        tuple(String.valueOf(school), "2026-08-20", "○○초등학교", false));
    }

    @Test
    void 같은_날은_기관_이름순이고_같은_일지는_한_줄이다() {
        Long school = journal(LocalDate.of(2026, 8, 19), schoolId);
        Long center = journal(LocalDate.of(2026, 8, 19), centerId);
        Long id = citing("[\"%d-0\",\"%d-0\",\"%d-2\"]".formatted(summaryOf(school), summaryOf(center),
                summaryOf(school)), null, null);

        assertThat(service.getInsight(MOTHER, id).evidence())
                .extracting(e -> e.institution().name())
                .containsExactly("○○초등학교", "햇살센터");
    }

    @Test
    void 대처에서만_인용한_지난_기록도_근거로_남는다() {
        Long past = journal(LocalDate.of(2026, 7, 1), centerId);
        Long notCited = journal(LocalDate.of(2026, 7, 8), centerId);
        Long id = citing("[]",
                "[{\"action\": \"예고\", \"result\": \"진정\", \"claim_ids\": [\"%d-0\"]}]".formatted(summaryOf(past)),
                "[\"%d-0\",\"%d-0\"]".formatted(summaryOf(past), summaryOf(notCited)));

        assertThat(service.getInsight(MOTHER, id).evidence())
                .extracting(GuardianInsightResponse.Evidence::journalEntryId, GuardianInsightResponse.Evidence::lookedUp)
                .containsExactly(tuple(String.valueOf(past), true));
    }

    @Test
    void 열_수_없는_근거는_그_줄만_빠진다() {
        Long ok = journal(LocalDate.of(2026, 8, 19), centerId);
        Long otherChild = em.persistAndFlush(Child.of("박소망", LocalDate.of(2019, 5, 1))).getId();
        Long otherSummary = em.persistAndFlush(SummaryResult.of(
                otherChild, LocalDate.of(2026, 8, 19), centerId, 1, "다른 아이", "[]", "[]", "[]", "{}")).getId();
        em.persistAndFlush(ChildContext.of(otherChild, otherSummary, "다른 아이"));
        Long unapproved = em.persistAndFlush(SummaryResult.of(
                childId, LocalDate.of(2026, 8, 20), centerId, 1, "검토 전", "[]", "[]", "[]", "{}")).getId();
        Long orphan = journal(LocalDate.of(2026, 8, 21), 9999L);
        Long id = citing("[\"%d-0\",\"%d-0\",\"%d-0\",\"%d-0\",\"987654-0\"]".formatted(
                summaryOf(ok), otherSummary, unapproved, summaryOf(orphan)), null, null);

        assertThat(service.getInsight(MOTHER, id).evidence())
                .extracting(GuardianInsightResponse.Evidence::journalEntryId)
                .containsExactly(String.valueOf(ok));
    }

    @Test
    void 동의를_끄고_명부에서_빠진_기관의_근거도_남고_그_일지가_열린다() {
        Long center = journal(LocalDate.of(2026, 8, 19), centerId);
        Long id = citing("[\"%d-0\"]".formatted(summaryOf(center)), null, null);
        em.persistAndFlush(ChildGuardian.link(childId, guardianId, 1L));
        em.find(SharingConsent.class, centerConsentId)
                .revoke(guardianId, ConsentRevokeReason.GUARDIAN_REVOKED, LocalDateTime.now());
        em.find(ChildOrganization.class, centerEnrollmentId).delete();
        em.flush();
        em.clear();

        assertThat(service.getInsight(MOTHER, id).evidence())
                .extracting(GuardianInsightResponse.Evidence::journalEntryId)
                .containsExactly(String.valueOf(center));
        assertThat(journalService.entry(MOTHER, center).id()).isEqualTo(String.valueOf(center));
    }

    @Test
    void 삭제된_아이의_글은_근거가_비어_있다() {
        Long center = journal(LocalDate.of(2026, 8, 19), centerId);
        Long id = citing("[\"%d-0\"]".formatted(summaryOf(center)), null, null);
        em.find(Child.class, childId).delete();
        em.flush();
        em.clear();

        assertThat(service.getInsight(MOTHER, id)).satisfies(insight -> {
            assertThat(insight.childName()).isNull();
            assertThat(insight.evidence()).isEmpty();
        });
    }

    @Test
    void 어느_인용_경로에도_유효한_근거가_없으면_빈_배열이고_글은_보인다() {
        Long id = citing("[\"깨진 번호\", 42]", "[{\"result\": \"번호 없는 대처\"}]", null);

        assertThat(service.getInsight(MOTHER, id).evidence()).isEmpty();
        assertThat(service.getInsight(MOTHER, insightId).evidence()).isEmpty();
    }

    @Test
    void 목록과_단건의_근거가_같다() {
        Long school = journal(LocalDate.of(2026, 8, 20), schoolId);
        Long center = journal(LocalDate.of(2026, 8, 18), centerId);
        Long id = citing("[\"%d-0\"]".formatted(summaryOf(school)), null, null);
        Long other = citing("[\"%d-0\"]".formatted(summaryOf(center)), null, null);

        List<GuardianInsightResponse> list = service.getInsights(MOTHER);

        assertThat(list).filteredOn(i -> i.id().equals(String.valueOf(id))).singleElement()
                .satisfies(i -> assertThat(i.evidence()).isEqualTo(service.getInsight(MOTHER, id).evidence()));
        assertThat(list).filteredOn(i -> i.id().equals(String.valueOf(other))).singleElement()
                .satisfies(i -> assertThat(i.evidence()).extracting(GuardianInsightResponse.Evidence::journalEntryId)
                        .containsExactly(String.valueOf(center)));
    }

    private Long journal(LocalDate date, Long institutionId) {
        Long summary = em.persistAndFlush(SummaryResult.of(
                childId, date, institutionId, 1, "요약", "[]", "[]", "[]", "{}")).getId();
        return em.persistAndFlush(ChildContext.of(childId, summary, "요약")).getId();
    }

    private Long summaryOf(Long journalId) {
        return em.find(ChildContext.class, journalId).getSummaryResultId();
    }

    private Long citing(String claimIds, String supports, String lookedUpClaimIds) {
        Long id = em.persistAndFlush(InsightResult.of(childId, FROM, TO, 1, "상황", "반응", "본문", supports,
                claimIds, "[]", "[%d]".formatted(schoolId), false, null, lookedUpClaimIds)).getId();
        em.persistAndFlush(SharingHistory.grant(childId, id, schoolId, ReceiverType.GUARDIAN, guardianId));
        em.clear();
        return id;
    }

    private Long insight(String content) {
        Long id = em.persistAndFlush(InsightResult.of(childId, FROM, TO, 2, "상황", "반응", content, null, "[]", "[]",
                "[%d]".formatted(schoolId), false, null)).getId();
        em.clear();
        return id;
    }

    private void grantToMother(Long id) {
        em.persistAndFlush(SharingHistory.grant(childId, id, schoolId, ReceiverType.GUARDIAN, guardianId));
        em.clear();
    }

    private void approve(Long id, LocalDateTime at) {
        Approval approval = Approval.gate2(id, 1L, ApprovalDecision.APPROVED);
        ReflectionTestUtils.setField(approval, "reviewedAt", at);
        em.persistAndFlush(approval);
        em.persistAndFlush(Approval.gate2(id, 1L, ApprovalDecision.HOLD));
        em.clear();
    }

    private void revoke(Long grantId, ConsentRevokeReason reason) {
        em.find(SharingHistory.class, grantId).revoke(reason, LocalDateTime.now());
        em.flush();
        em.clear();
    }
}
