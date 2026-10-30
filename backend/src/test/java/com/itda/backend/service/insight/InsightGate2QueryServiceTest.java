package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.response.insight.Gate2InsightResponse;
import com.itda.backend.dto.response.insight.Gate2InsightResponse.Target;
import com.itda.backend.exception.insight.InsightErrorCode;
import com.itda.backend.exception.insight.InsightException;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

/** Gate 2 검토 목록·단건. 승인 주체인 기관에만, 최신 회차만 보인다. */
@DataJpaTest
@ActiveProfiles("test")
@Import({InsightGate2QueryService.class, ConsentStateReader.class})
class InsightGate2QueryServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 17);
    private static final LocalDate TO = LocalDate.of(2026, 8, 23);
    private static final String SCHOOL_TEACHER = "99";
    private static final String CENTER_TEACHER = "98";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private InsightGate2QueryService service;

    @MockitoBean
    private UserService userService;

    private Long childId;
    private Long schoolId;
    private Long centerId;
    private Long assistantId;
    private Long schoolLinkId;
    private Long summaryId;
    private Long contextId;

    @BeforeEach
    void setUp() {
        childId = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2018, 3, 2))).getId();
        schoolId = em.persistAndFlush(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001")).getId();
        centerId = em.persistAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1000000002")).getId();
        assistantId = em.persistAndFlush(
                Organization.of("△△활동지원", OrganizationType.ACTIVITY_SUPPORT, "1000000003")).getId();

        schoolLinkId = em.persistAndFlush(ChildOrganization.of(childId, schoolId)).getId();
        em.persistAndFlush(ChildOrganization.of(childId, centerId));
        em.persistAndFlush(ChildOrganization.of(childId, assistantId));

        // 학교·센터는 주간 인사이트까지 동의했다. 활동지원사는 동의 행 자체가 없다.
        // 재료를 낸 기관은 동의가 있어야 목록에 보인다 (#257) — 재료를 고를 때도 같은 동의가 필요하다.
        em.persistAndFlush(SharingConsent.grant(childId, 7L, schoolId, List.of(ConsentField.WEEKLY_INSIGHT)));
        em.persistAndFlush(SharingConsent.grant(childId, 7L, centerId, List.of(ConsentField.WEEKLY_INSIGHT)));

        SummaryResult summary = em.persistAndFlush(
                SummaryResult.of(childId, LocalDate.of(2026, 8, 19), centerId, 1, "블록을 쌓았다.", "[]", "[]", "[]", "{}"));
        summaryId = summary.getId();
        contextId = em.persistAndFlush(ChildContext.of(childId, summaryId, "블록을 쌓았다.")).getId();

        given(userService.getOrganizationIdOf(SCHOOL_TEACHER)).willReturn(schoolId);
        given(userService.getOrganizationIdOf(CENTER_TEACHER)).willReturn(centerId);
        em.clear();
    }

    @Test
    void 승인_주체인_기관에만_보인다() {
        insight(1, schoolId, centerId);

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending")).hasSize(1);
        // 센터도 근거를 냈지만 학교가 승인 주체라 목록에서는 조용히 빠진다.
        assertThat(service.getInsights(CENTER_TEACHER, "pending")).isEmpty();
    }

    @Test
    void 최신_회차만_보인다() {
        insight(1, schoolId);
        InsightResult latest = insight(2, schoolId);

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending"))
                .singleElement()
                .extracting(Gate2InsightResponse::id)
                .isEqualTo(String.valueOf(latest.getId()));
    }

    /** 최신 회차가 이미 승인됐으면 옛 회차의 대기 행이 최신인 척하면 안 된다. */
    @Test
    void 최신_회차가_승인됐으면_옛_회차도_대기_목록에_없다() {
        insight(1, schoolId);
        Long latestId = insight(2, schoolId).getId();
        em.find(InsightResult.class, latestId).approveGate2();
        em.flush();
        em.clear();

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending")).isEmpty();
        assertThat(service.getInsights(SCHOOL_TEACHER, "approved")).hasSize(1);
    }

    @Test
    void 수신_후보에_승인_기관_자신은_빠지고_동의_상태가_붙는다() {
        insight(1, schoolId, centerId);

        List<Target> targets = service.getInsights(SCHOOL_TEACHER, "pending").get(0).targets();

        assertThat(targets).extracting(t -> t.institution().id())
                .containsExactly(String.valueOf(centerId), String.valueOf(assistantId))
                .doesNotContain(String.valueOf(schoolId));
        assertThat(targets).extracting(Target::consent)
                .containsExactly(ConsentState.GRANTED, ConsentState.NOT_GRANTED);
    }

    @Test
    void 근거는_승인본과_날짜_기관을_가리킨다() {
        insight(1, schoolId, centerId);

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending").get(0).evidence())
                .singleElement().satisfies(ref -> {
                    assertThat(ref.childContextId()).isEqualTo(String.valueOf(contextId));
                    assertThat(ref.date()).isEqualTo("2026-08-19");
                    assertThat(ref.institution().id()).isEqualTo(String.valueOf(centerId));
                    assertThat(ref.label()).isEqualTo("관찰일지 요약");
                });
    }

    /** Gate 1 을 통과하지 않은 요약은 근거가 아니다 — 승인본이 없으면 그 줄을 뺀다. */
    @Test
    void 승인본이_없는_요약은_근거에서_빠진다() {
        SummaryResult unapproved = em.persistAndFlush(SummaryResult.of(
                childId, LocalDate.of(2026, 8, 20), centerId, 1, "미승인", "[]", "[]", "[]", "{}"));
        em.persistAndFlush(InsightResult.of(childId, FROM, TO, 1, "상황", "반응", "본문", null,
                "[\"%d-0\"]".formatted(unapproved.getId()), "[]", "[%d]".formatted(schoolId), false, null));
        em.clear();

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending").get(0).evidence()).isEmpty();
    }

    /** 다른 기관 아이의 인사이트가 목록에 섞이면 안 된다. 명부가 유일한 울타리다. */
    @Test
    void 우리_명부에_없는_아이는_목록에_없다() {
        Long otherChild = em.persistAndFlush(Child.of("박소망", LocalDate.of(2019, 5, 1))).getId();
        em.persistAndFlush(ChildOrganization.of(otherChild, centerId));
        em.persistAndFlush(InsightResult.of(otherChild, FROM, TO, 1, "상황", "반응", "본문", null,
                "[]", "[]", "[%d]".formatted(schoolId), false, null));
        em.clear();

        // 학교가 근거를 냈다고 적혀 있어도 그 아이는 학교 명부에 없다.
        assertThat(service.getInsights(SCHOOL_TEACHER, "pending")).isEmpty();
    }

    /** 아이가 우리 기관을 떠난 뒤에는 본문도 이름도 보이면 안 된다 — source_institution_ids 는 굳은 값이다. */
    @Test
    void 연결이_끊긴_뒤에는_단건도_404() {
        InsightResult insight = insight(1, schoolId, centerId);
        em.find(ChildOrganization.class, schoolLinkId).delete();
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.getInsight(SCHOOL_TEACHER, insight.getId()))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.NOT_FOUND);
    }

    /** 목록에서 뺀 아이다. 인사이트 재료를 모을 때와 같은 기준으로 가린다. */
    @Test
    void 삭제된_아이의_인사이트는_보이지_않는다() {
        insight(1, schoolId);
        em.find(Child.class, childId).delete();
        em.flush();
        em.clear();

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending")).isEmpty();
    }

    /**
     * claim_id 는 AI 가 쓴 글자다. 숫자가 어긋나 다른 아이 요약을 가리키면 그 아이의 기관·날짜·승인본 id 가
     * 상관없는 교사 화면에 뜬다.
     */
    @Test
    void 다른_아이의_요약을_가리키는_근거는_빠진다() {
        Long otherChild = em.persistAndFlush(Child.of("박소망", LocalDate.of(2019, 5, 1))).getId();
        SummaryResult otherSummary = em.persistAndFlush(SummaryResult.of(
                otherChild, LocalDate.of(2026, 8, 19), assistantId, 1, "다른 아이", "[]", "[]", "[]", "{}"));
        em.persistAndFlush(ChildContext.of(otherChild, otherSummary.getId(), "다른 아이"));
        em.persistAndFlush(InsightResult.of(childId, FROM, TO, 1, "상황", "반응", "본문", null,
                "[\"%d-0\",\"%d-0\"]".formatted(summaryId, otherSummary.getId()),
                "[]", "[%d]".formatted(schoolId), false, null));
        em.clear();

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending").get(0).evidence())
                .singleElement()
                .satisfies(ref -> assertThat(ref.childContextId()).isEqualTo(String.valueOf(contextId)));
    }

    /** 같은 요약이 여러 claim 의 근거여도 화면에는 한 줄이다. */
    @Test
    void 같은_요약은_근거에_한_번만_나온다() {
        em.persistAndFlush(InsightResult.of(childId, FROM, TO, 1, "상황", "반응", "본문", null,
                "[\"%d-0\",\"%d-2\",\"%d-5\"]".formatted(summaryId, summaryId, summaryId),
                "[]", "[%d]".formatted(schoolId), false, null));
        em.clear();

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending").get(0).evidence()).hasSize(1);
    }

    /** 기관 행이 사라진 근거는 이름을 모른다. 출처 없는 줄을 올리느니 뺀다. */
    @Test
    void 기관을_찾을_수_없는_근거는_빠진다() {
        SummaryResult orphan = em.persistAndFlush(SummaryResult.of(
                childId, LocalDate.of(2026, 8, 20), 9999L, 1, "사라진 기관", "[]", "[]", "[]", "{}"));
        em.persistAndFlush(ChildContext.of(childId, orphan.getId(), "사라진 기관"));
        em.persistAndFlush(InsightResult.of(childId, FROM, TO, 1, "상황", "반응", "본문", null,
                "[\"%d-0\"]".formatted(orphan.getId()), "[]", "[%d]".formatted(schoolId), false, null));
        em.clear();

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending").get(0).evidence()).isEmpty();
    }

    /** 철회 행이 ACTIVE 행을 덮으면 동의한 기관이 미동의로 보인다. 같은 기관에 두 행이 있을 수 있다. */
    @Test
    void 철회_이력이_있어도_살아_있는_동의가_이긴다() {
        SharingConsent revoked = SharingConsent.grant(childId, 8L, centerId, List.of(ConsentField.WEEKLY_INSIGHT));
        revoked.revoke(8L, ConsentRevokeReason.GUARDIAN_REVOKED, LocalDateTime.now());
        em.persistAndFlush(revoked);
        insight(1, schoolId, centerId);

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending").get(0).targets())
                .filteredOn(t -> t.institution().id().equals(String.valueOf(centerId)))
                .singleElement()
                .extracting(Target::consent)
                .isEqualTo(ConsentState.GRANTED);
    }

    @Test
    void 기간은_화면_문구로_나간다() {
        insight(1, schoolId);

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending").get(0).period())
                .isEqualTo("2026년 8월 3주차");
    }

    /** 7일 경계에서 주차가 바뀐다. 14일은 2주차, 15일은 3주차다. */
    @Test
    void 주차는_7일마다_올라간다() {
        em.persistAndFlush(InsightResult.of(childId, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), 1,
                "상황", "반응", "본문", null, "[]", "[]", "[%d]".formatted(schoolId), false, null));
        em.clear();

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending"))
                .extracting(Gate2InsightResponse::period)
                .containsExactly("2026년 9월 2주차");
    }

    @Test
    void 지원하지_않는_상태는_400() {
        assertThatThrownBy(() -> service.getInsights(SCHOOL_TEACHER, "sent"))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.INVALID_REQUEST);
    }

    /** 목록은 조용히 빼지만 단건은 왜 못 보는지 알려준다. */
    @Test
    void 단건은_승인_주체가_아니면_403() {
        InsightResult insight = insight(1, schoolId, centerId);

        assertThat(service.getInsight(SCHOOL_TEACHER, insight.getId()).id())
                .isEqualTo(String.valueOf(insight.getId()));
        assertThatThrownBy(() -> service.getInsight(CENTER_TEACHER, insight.getId()))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.NOT_PRIMARY_SOURCE);
    }

    @Test
    void 단건은_근거를_내지_않은_기관에_404() {
        InsightResult insight = insight(1, centerId);

        assertThatThrownBy(() -> service.getInsight(SCHOOL_TEACHER, insight.getId()))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.NOT_FOUND);
    }

    /**
     * 재료를 낸 센터가 동의를 철회하면, 승인하는 학교도 그 글을 더 못 읽는다 (#257, 멘토님 리뷰 #256 P1).
     * 승인 버튼만 막으면 목록·상세로 철회된 기관 내용이 섞인 글을 계속 본다. 이미 승인한 글도 같다.
     */
    @Test
    void 재료_기관이_동의를_철회하면_목록과_상세에서_사라진다() {
        InsightResult pending = insight(1, schoolId, centerId);
        assertThat(service.getInsights(SCHOOL_TEACHER, "pending")).hasSize(1);

        revokeConsentsTo(centerId);

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending")).isEmpty();
        assertThatThrownBy(() -> service.getInsight(SCHOOL_TEACHER, pending.getId()))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.NOT_FOUND);
    }

    @Test
    void 이미_승인한_글도_재료_기관이_철회하면_사라진다() {
        InsightResult approved = insight(1, schoolId, centerId);
        InsightResult loaded = em.find(InsightResult.class, approved.getId());
        loaded.approveGate2();
        em.flush();
        em.clear();
        assertThat(service.getInsights(SCHOOL_TEACHER, "approved")).hasSize(1);

        revokeConsentsTo(centerId);

        assertThat(service.getInsights(SCHOOL_TEACHER, "approved")).isEmpty();
    }

    /** 하루 요약만 허락한 기관은 승인해도 받지 않으므로, 수신 후보에 미동의로 보인다 (#257). */
    @Test
    void 하루_요약만_허락한_기관은_수신_후보에서_미동의로_보인다() {
        em.persistAndFlush(SharingConsent.grant(childId, 7L, assistantId, List.of(ConsentField.DAILY_SUMMARY)));
        insight(1, schoolId);

        assertThat(service.getInsights(SCHOOL_TEACHER, "pending").get(0).targets())
                .filteredOn(t -> t.institution().id().equals(String.valueOf(assistantId)))
                .singleElement()
                .extracting(Target::consent)
                .isEqualTo(ConsentState.NOT_GRANTED);
    }

    private void revokeConsentsTo(Long organizationId) {
        em.getEntityManager()
                .createQuery("select c from SharingConsent c where c.childId = :childId "
                        + "and c.organizationId = :organizationId", SharingConsent.class)
                .setParameter("childId", childId)
                .setParameter("organizationId", organizationId)
                .getResultList()
                .forEach(c -> c.revoke(7L, ConsentRevokeReason.GUARDIAN_REVOKED, LocalDateTime.now()));
        em.flush();
        em.clear();
    }

    private InsightResult insight(int revision, Long... sourceInstitutionIds) {
        String json = "[" + String.join(",",
                java.util.Arrays.stream(sourceInstitutionIds).map(String::valueOf).toList()) + "]";
        InsightResult saved = em.persistAndFlush(InsightResult.of(childId, FROM, TO, revision,
                "급식실 입장 직후", "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.", null,
                "[\"%d-0\"]".formatted(summaryId), "[1041]", json, sourceInstitutionIds.length > 1, null));
        em.clear();
        return saved;
    }
}
