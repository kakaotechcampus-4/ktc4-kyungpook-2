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

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.ApprovalReviewType;
import com.itda.backend.domain.approval.ApprovalTargetType;
import com.itda.backend.domain.approval.Gate2Action;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.insight.Gate2DecisionResponse;
import com.itda.backend.exception.insight.InsightErrorCode;
import com.itda.backend.exception.insight.InsightException;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

/** Gate 2 — 교사가 인사이트를 발송 전에 승인·보류한다. 기관 경계를 넘는 마지막 관문이다. */
@DataJpaTest
@ActiveProfiles("test")
@Import({InsightGate2Service.class, ConsentStateReader.class})
class InsightGate2ServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 17);
    private static final LocalDate TO = LocalDate.of(2026, 8, 23);
    private static final String TEACHER = "99";
    private static final String OTHER_TEACHER = "98";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private InsightGate2Service service;

    @Autowired
    private SharingHistoryRepository sharingHistoryRepository;

    @Autowired
    private ApprovalRepository approvalRepository;

    @MockitoBean
    private UserService userService;

    private Long childId;
    private Long schoolId;
    private Long centerId;
    private Long schoolLinkId;
    private Long guardianId;

    @BeforeEach
    void setUp() {
        childId = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2018, 3, 2))).getId();
        schoolId = em.persistAndFlush(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001")).getId();
        centerId = em.persistAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1000000002")).getId();
        schoolLinkId = em.persistAndFlush(ChildOrganization.of(childId, schoolId)).getId();
        em.persistAndFlush(ChildOrganization.of(childId, centerId));
        guardianId = 7L;
        em.persistAndFlush(ChildGuardian.link(childId, guardianId, 1L));
        // 재료 기관의 공유 동의. 인사이트를 만들 때 있었던 것이고, 승인 시점에 다시 본다 (#213).
        consent(schoolId);
        consent(centerId);
        given(userService.getOrganizationIdOf(TEACHER)).willReturn(schoolId);
        given(userService.getOrganizationIdOf(OTHER_TEACHER)).willReturn(centerId);
        em.clear();
    }

    @Test
    void 승인하면_보호자가_볼_수_있게_되고_결정이_남는다() {
        InsightResult insight = insight(1, schoolId, centerId);

        Gate2DecisionResponse response = service.decide(TEACHER, insight.getId(), Gate2Action.APPROVE);

        assertThat(response.gate2Status()).isEqualTo("approved");
        assertThat(response.decision()).isEqualTo("APPROVED");
        assertThat(reload(insight).getStatus()).isEqualTo(InsightStatus.APPROVED);

        List<SharingHistory> granted = sharingHistoryRepository.findByInsightResultId(insight.getId());
        assertThat(granted).filteredOn(history -> history.getReceiverType() == ReceiverType.GUARDIAN)
                .singleElement().satisfies(history -> {
            assertThat(history.getReceiverId()).isEqualTo(guardianId);
            // 승인한 기관이 발신이다 — 수신함이 "어디서 왔나" 로 보여준다.
            assertThat(history.getSenderOrganizationId()).isEqualTo(schoolId);
            assertThat(history.getGrantedAt()).isNotNull();
            assertThat(history.getReadAt()).isNull();
        });

        assertThat(approvalRepository.findAll()).singleElement().satisfies(approval -> {
            assertThat(approval.getTargetType()).isEqualTo(ApprovalTargetType.INSIGHT);
            assertThat(approval.getTargetId()).isEqualTo(insight.getId());
            assertThat(approval.getReviewType()).isEqualTo(ApprovalReviewType.GATE2);
            assertThat(approval.getDecision()).isEqualTo(ApprovalDecision.APPROVED);
        });
    }

    /**
     * 보호자가 미리 해 둔 기관별 동의를 그대로 쓴다 (#233). 글마다 고르는 단계는 없다. 받는 기관은 명부에 있고,
     * 동의가 켜져 있는 곳이다. 승인한 기관 자신만 뺀다.
     */
    @Test
    void 승인하면_동의한_기관도_바로_볼_수_있다() {
        Long academyId = em.persistAndFlush(
                Organization.of("△△활동지원", OrganizationType.ACTIVITY_SUPPORT, "1000000003")).getId();
        Long formerId = em.persistAndFlush(
                Organization.of("예전센터", OrganizationType.CENTER, "1000000004")).getId();
        em.persistAndFlush(ChildOrganization.of(childId, academyId)); // 명부에는 있지만 동의가 없다
        consent(formerId);                                               // 동의는 있지만 명부에 없다
        InsightResult insight = insight(1, schoolId);                   // 재료는 학교만

        service.decide(TEACHER, insight.getId(), Gate2Action.APPROVE);

        assertThat(sharingHistoryRepository.findByInsightResultId(insight.getId()))
                .filteredOn(history -> history.getReceiverType() == ReceiverType.ORGANIZATION)
                .singleElement().satisfies(history -> {
                    assertThat(history.getReceiverId()).isEqualTo(centerId);
                    assertThat(history.getSenderOrganizationId()).isEqualTo(schoolId);
                    assertThat(history.isActive()).isTrue();
                });
    }

    /**
     * 재료를 낸 다른 기관도 받는다. Gate 2 목록은 승인 기관에게만 보여서, 여기서 빼면 센터는 자기 기록이 섞인
     * 기관 간 인사이트를 영영 못 본다. 승인 기관 자신만 뺀다.
     */
    @Test
    void 재료를_낸_다른_기관도_받고_승인_기관_자신은_받지_않는다() {
        InsightResult insight = insight(1, schoolId, centerId);

        service.decide(TEACHER, insight.getId(), Gate2Action.APPROVE);

        assertThat(sharingHistoryRepository.findByInsightResultId(insight.getId()))
                .filteredOn(history -> history.getReceiverType() == ReceiverType.ORGANIZATION)
                .extracting(SharingHistory::getReceiverId)
                .containsExactly(centerId);
    }

    @Test
    void 보류하면_아무도_볼_수_없다() {
        InsightResult insight = insight(1, schoolId); // 승인했다면 센터가 받았을 글이다

        Gate2DecisionResponse response = service.decide(TEACHER, insight.getId(), Gate2Action.HOLD);

        assertThat(response.gate2Status()).isEqualTo("hold");
        assertThat(reload(insight).getStatus()).isEqualTo(InsightStatus.HOLD);
        assertThat(sharingHistoryRepository.findByInsightResultId(insight.getId())).isEmpty();
        assertThat(approvalRepository.findAll()).singleElement()
                .extracting(Approval::getDecision).isEqualTo(ApprovalDecision.HOLD);
    }

    /** 학교 > 센터. 근거는 냈지만 승인 주체가 아닌 기관은 자기 기록이 쓰인 것은 아니까 403 으로 답한다. */
    @Test
    void 승인_주체가_아닌_근거_기관은_403() {
        InsightResult insight = insight(1, schoolId, centerId);

        assertThatThrownBy(() -> service.decide(OTHER_TEACHER, insight.getId(), Gate2Action.APPROVE))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.NOT_PRIMARY_SOURCE);
        assertThat(reload(insight).getStatus()).isEqualTo(InsightStatus.GATE2_PENDING);
    }

    /** 근거를 내지 않은 기관에는 존재 여부조차 알려주지 않는다 (deny-by-default). */
    @Test
    void 근거를_내지_않은_기관은_404() {
        InsightResult insight = insight(1, centerId);

        assertThatThrownBy(() -> service.decide(TEACHER, insight.getId(), Gate2Action.APPROVE))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.NOT_FOUND);
    }

    @Test
    void 이미_처리된_인사이트는_409() {
        InsightResult insight = insight(1, schoolId);
        service.decide(TEACHER, insight.getId(), Gate2Action.APPROVE);
        // 테스트는 한 트랜잭션이라 flush 없이 clear 하면 바뀐 상태가 버려진다. 실제로는 요청마다 커밋된다.
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.decide(TEACHER, insight.getId(), Gate2Action.HOLD))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.ALREADY_REVIEWED);
    }

    /** 재료가 늘어 새 회차가 만들어졌다. 옛 회차를 승인하면 철 지난 글이 다른 기관으로 나간다. */
    @Test
    void 옛_회차는_승인할_수_없다() {
        InsightResult old = insight(1, schoolId);
        insight(2, schoolId);
        em.clear();

        assertThatThrownBy(() -> service.decide(TEACHER, old.getId(), Gate2Action.APPROVE))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.SUPERSEDED);
        assertThat(reload(old).getStatus()).isEqualTo(InsightStatus.GATE2_PENDING);
    }

    /** 초대를 아직 수락하지 않은 아이다. 승인은 되지만 아무에게도 보이지 않는다 — 오류가 아니다. */
    @Test
    void 보호자가_없으면_승인은_되지만_열람_권한이_생기지_않는다() {
        Long orphan = em.persistAndFlush(Child.of("박소망", LocalDate.of(2019, 5, 1))).getId();
        em.persistAndFlush(ChildOrganization.of(orphan, schoolId));
        em.persistAndFlush(SharingConsent.grant(orphan, guardianId, schoolId, List.of(ConsentField.WEEKLY_INSIGHT)));
        InsightResult insight = em.persistAndFlush(insightOf(orphan, 1, schoolId));
        em.clear();

        service.decide(TEACHER, insight.getId(), Gate2Action.APPROVE);

        assertThat(reload(insight).getStatus()).isEqualTo(InsightStatus.APPROVED);
        assertThat(sharingHistoryRepository.findByInsightResultId(insight.getId())).isEmpty();
    }

    /** 근거 기관 JSON 이 깨졌다. 아무도 승인 주체가 아니므로 통째로 막는다 — 조용히 아무나 승인하면 안 된다. */
    @Test
    void 근거_기관을_읽을_수_없으면_404() {
        InsightResult insight = em.persistAndFlush(InsightResult.of(childId, FROM, TO, 1,
                "상황", "반응", "본문", null, "[]", "[]", "{깨진 JSON", false, null));
        em.clear();

        assertThatThrownBy(() -> service.decide(TEACHER, insight.getId(), Gate2Action.APPROVE))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.NOT_FOUND);
    }

    /** 아이가 우리 기관을 떠났다. source_institution_ids 는 굳은 값이라 그것만 보면 계속 승인된다. */
    @Test
    void 연결이_끊긴_기관은_승인할_수_없다() {
        InsightResult insight = insight(1, schoolId, centerId);
        em.find(ChildOrganization.class, schoolLinkId).delete();
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.decide(TEACHER, insight.getId(), Gate2Action.APPROVE))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.NOT_FOUND);
        assertThat(reload(insight).getStatus()).isEqualTo(InsightStatus.GATE2_PENDING);
    }

    /**
     * 인사이트를 만들 때는 동의가 있었지만 교사가 승인을 누르기 전에 보호자가 철회했다.
     * 여기서 막지 않으면 철회된 기관의 기록이 섞인 글이 그대로 나간다 (#213).
     */
    @Test
    void 재료_기관의_동의가_철회되면_승인할_수_없다() {
        InsightResult insight = insight(1, schoolId, centerId);
        revokeConsent(centerId);

        assertThatThrownBy(() -> service.decide(TEACHER, insight.getId(), Gate2Action.APPROVE))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.MATERIAL_CONSENT_REVOKED);
        assertThat(reload(insight).getStatus()).isEqualTo(InsightStatus.GATE2_PENDING);
        assertThat(sharingHistoryRepository.findByInsightResultId(insight.getId())).isEmpty();
    }

    /** 보류는 아무 데도 안 나간다. 막으면 교사가 철회된 인사이트를 치울 길이 없어진다. */
    @Test
    void 동의가_철회돼도_보류는_할_수_있다() {
        InsightResult insight = insight(1, schoolId, centerId);
        revokeConsent(centerId);

        service.decide(TEACHER, insight.getId(), Gate2Action.HOLD);

        assertThat(reload(insight).getStatus()).isEqualTo(InsightStatus.HOLD);
    }

    /** 목록에서 뺀 아이의 글은 더 나가지 않는다. */
    @Test
    void 삭제된_아이는_승인할_수_없다() {
        InsightResult insight = insight(1, schoolId);
        em.find(Child.class, childId).delete();
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.decide(TEACHER, insight.getId(), Gate2Action.APPROVE))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.NOT_FOUND);
    }

    /** 하루 요약만 허락한 기관은 주간 인사이트를 받지 않는다 (#257, 멘토님 리뷰 #256 P1). */
    @Test
    void 하루_요약만_허락한_기관은_인사이트를_받지_않는다() {
        Long dailyOnlyId = em.persistAndFlush(
                Organization.of("하루요약만활동지원", OrganizationType.ACTIVITY_SUPPORT, "1000000005")).getId();
        em.persistAndFlush(ChildOrganization.of(childId, dailyOnlyId));
        em.persistAndFlush(SharingConsent.grant(childId, guardianId, dailyOnlyId, List.of(ConsentField.DAILY_SUMMARY)));
        InsightResult insight = insight(1, schoolId);

        service.decide(TEACHER, insight.getId(), Gate2Action.APPROVE);

        assertThat(sharingHistoryRepository.findByInsightResultId(insight.getId()))
                .filteredOn(history -> history.getReceiverType() == ReceiverType.ORGANIZATION)
                .extracting(SharingHistory::getReceiverId)
                .containsExactly(centerId);
    }

    /** 재료를 낸 기관이 범위에서 주간 인사이트를 뺐으면 철회와 같이 본다 — 승인할 수 없다 (#257). */
    @Test
    void 재료_기관이_주간_인사이트를_빼면_승인할_수_없다() {
        InsightResult insight = insight(1, schoolId, centerId);
        em.getEntityManager()
                .createQuery("select c from SharingConsent c where c.childId = :childId "
                        + "and c.organizationId = :organizationId", SharingConsent.class)
                .setParameter("childId", childId)
                .setParameter("organizationId", centerId)
                .getResultList()
                .forEach(c -> c.changeScope(List.of(ConsentField.DAILY_SUMMARY)));
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.decide(TEACHER, insight.getId(), Gate2Action.APPROVE))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).getErrorCode())
                .isEqualTo(InsightErrorCode.MATERIAL_CONSENT_REVOKED);
    }

    private void revokeConsent(Long organizationId) {
        em.getEntityManager()
                .createQuery("select c from SharingConsent c where c.childId = :childId "
                        + "and c.organizationId = :organizationId", SharingConsent.class)
                .setParameter("childId", childId)
                .setParameter("organizationId", organizationId)
                .getResultList()
                .forEach(c -> c.revoke(guardianId, ConsentRevokeReason.GUARDIAN_REVOKED, LocalDateTime.now()));
        em.flush();
        em.clear();
    }

    private void consent(Long organizationId) {
        em.persistAndFlush(SharingConsent.grant(childId, guardianId, organizationId,
                List.of(ConsentField.WEEKLY_INSIGHT)));
    }

    private InsightResult insight(int revision, Long... sourceInstitutionIds) {
        InsightResult saved = em.persistAndFlush(insightOf(childId, revision, sourceInstitutionIds));
        em.clear();
        return saved;
    }

    private InsightResult insightOf(Long child, int revision, Long... sourceInstitutionIds) {
        String json = "[" + String.join(",",
                java.util.Arrays.stream(sourceInstitutionIds).map(String::valueOf).toList()) + "]";
        return InsightResult.of(child, FROM, TO, revision, "급식실 입장 직후", "소리를 지름",
                "이동 전 5분 예고가 효과적이었습니다.", null, "[\"31-0\"]", "[1041]", json,
                sourceInstitutionIds.length > 1, null);
    }

    private InsightResult reload(InsightResult insight) {
        em.flush();
        em.clear();
        return em.find(InsightResult.class, insight.getId());
    }
}
