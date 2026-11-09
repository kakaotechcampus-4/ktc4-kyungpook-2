package com.itda.backend.service.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.child.ChildStatus;
import com.itda.backend.domain.consent.ConsentAction;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.consent.SharingConsentStatus;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.invitation.Invitation;
import com.itda.backend.domain.invitation.InvitationStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.request.child.RegisterChildRequest;
import com.itda.backend.dto.response.insight.Gate2InsightResponse;
import com.itda.backend.dto.response.sharing.InboxItemResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.exception.sharing.SharingErrorCode;
import com.itda.backend.exception.sharing.SharingException;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.service.consent.ConsentService;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.insight.InsightAccessGranter;
import com.itda.backend.service.insight.InsightAccessRevoker;
import com.itda.backend.service.insight.InsightEvidenceReader;
import com.itda.backend.service.insight.InsightGate2QueryService;
import com.itda.backend.service.invitation.InvitationService;
import com.itda.backend.service.invitation.InviteCodeGenerator;
import com.itda.backend.service.sharing.InstitutionInboxService;
import com.itda.backend.service.sharing.SharingReadRecorder;
import com.itda.backend.service.user.UserService;

/**
 * O-63 명부에서 빼기. 아이는 우리 센터와 학교에 함께 다니고, 엄마가 센터 코드로 연결돼 두 기관 모두에 주간 리포트까지
 * 동의했다. 동의 철회·열람권 회수·초대 코드 취소를 진짜 서비스로 엮어, 배선이 빠지면 여기서 드러나게 한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({ChildService.class, OrganizationChildGuard.class, GuardianChildGuard.class, ConsentService.class, ConsentStateReader.class,
        InsightAccessRevoker.class, InvitationService.class, InstitutionLinkService.class,
        InstitutionInboxService.class, SharingReadRecorder.class, InsightGate2QueryService.class,
        InsightEvidenceReader.class, ChildRosterRemovalJpaTest.ClockConfig.class})
class ChildRosterRemovalJpaTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 10, 0);
    private static final String OUR_STAFF = "u-center";
    private static final String SCHOOL_STAFF = "u-school";
    private static final String MOTHER = "u-mother";
    private static final Long OUR_STAFF_ID = 777L;
    private static final String CENTER_CODE = "CENTERAB";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private ChildService childService;

    @Autowired
    private InvitationService invitationService;

    @Autowired
    private ConsentService consentService;

    @Autowired
    private InstitutionLinkService institutionLinkService;

    @Autowired
    private InstitutionInboxService institutionInboxService;

    @Autowired
    private InsightGate2QueryService insightGate2QueryService;

    @Autowired
    private TestEntityManager em;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private InsightAccessGranter insightAccessGranter;

    @MockitoBean
    private InviteCodeGenerator inviteCodeGenerator;

    private Organization center;
    private Organization school;
    private Child child;
    private Long motherId;
    private Long ourLinkId;

    @BeforeEach
    void setUp() {
        center = Organization.of("햇살센터", OrganizationType.CENTER, "1000000002");
        center.assignCode(CENTER_CODE);
        center = em.persist(center);
        school = em.persist(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001"));
        child = em.persist(Child.of("김하늘", LocalDate.of(2018, 3, 2)));
        ourLinkId = em.persist(ChildOrganization.of(child.getId(), center.getId(), "C-7")).getId();
        em.persist(ChildOrganization.of(child.getId(), school.getId(), "S-1"));
        motherId = 7L;
        Invitation used = em.persist(Invitation.issue(child.getId(), center.getId(), "USEDCD23", NOW.plusMinutes(5)));
        used.accept(motherId, NOW.minusMinutes(1));
        em.persist(ChildGuardian.link(child.getId(), motherId, used.getId()));
        em.flush();
        em.clear();

        given(userService.getOrganizationIdOf(OUR_STAFF)).willReturn(center.getId());
        given(userService.getOrganizationIdOf(SCHOOL_STAFF)).willReturn(school.getId());
        given(userService.getUserIdOf(OUR_STAFF)).willReturn(OUR_STAFF_ID);
        given(userService.getParentIdOf(MOTHER)).willReturn(motherId);
        given(inviteCodeGenerator.generate()).willReturn("NEWCD234", "NEWCD235", "NEWCD236");
    }

    // ── 명부 연결 ─────────────────────────────────────────

    @Test
    void 명부_연결에_삭제_시각이_찍히고_관리번호가_비워진다() {
        childService.removeFromRoster(OUR_STAFF, child.getId());

        ChildOrganization link = reload(ChildOrganization.class, ourLinkId);
        assertThat(link.isDeleted()).isTrue();
        assertThat(link.getExternalId()).isNull();
        assertThat(childService.getRoster(OUR_STAFF)).isEmpty();
        assertThat(childService.getRoster(SCHOOL_STAFF)).hasSize(1);
    }

    @Test
    void 뺀_뒤에는_같은_관리번호와_이름_생일로_다시_등록할_수_있다() {
        childService.removeFromRoster(OUR_STAFF, child.getId());
        em.flush();

        childService.register(OUR_STAFF, new RegisterChildRequest("김하늘", "2018-03-02", "C-7"));
        em.flush();
        em.clear();

        assertThat(childService.getRoster(OUR_STAFF)).singleElement()
                .satisfies(row -> assertThat(row.externalId()).isEqualTo("C-7"));
    }

    // ── 동의와 아이 상태 ───────────────────────────────────

    @Test
    void 우리_기관_동의만_명부_제외로_꺼지고_뺀_담당자가_남는다() {
        grantBoth();

        childService.removeFromRoster(OUR_STAFF, child.getId());

        assertThat(consentOf(center)).satisfies(consent -> {
            assertThat(consent.getStatus()).isEqualTo(SharingConsentStatus.REVOKED);
            assertThat(consent.getRevokeReason()).isEqualTo(ConsentRevokeReason.ROSTER_REMOVED);
            assertThat(consent.getRevokedBy()).isEqualTo(OUR_STAFF_ID);
        });
        assertThat(consentOf(school).getStatus()).isEqualTo(SharingConsentStatus.ACTIVE);
        assertThat(reload(Child.class, child.getId()).getStatus()).isEqualTo(ChildStatus.ACTIVE);
    }

    @Test
    void 남은_동의가_없으면_아이는_공유_중단이_된다() {
        consentService.update(MOTHER, child.getId(), center.getId(), ConsentAction.GRANT,
                List.of(ConsentField.WEEKLY_INSIGHT));

        childService.removeFromRoster(OUR_STAFF, child.getId());

        assertThat(reload(Child.class, child.getId()).getStatus()).isEqualTo(ChildStatus.SUSPENDED);
    }

    /** 동의 이력이 없으면 끌 것도 없다. 동의 대기 그대로다 — 공유 중단으로 바꾸면 안 된다. */
    @Test
    void 한_번도_동의하지_않은_아이는_동의_대기_그대로다() {
        childService.removeFromRoster(OUR_STAFF, child.getId());

        assertThat(reload(Child.class, child.getId()).getStatus()).isEqualTo(ChildStatus.PENDING_CONSENT);
    }

    // ── 초대 코드 ────────────────────────────────────────

    @Test
    void 우리_기관의_쓸_수_있는_코드만_취소한다() {
        Long ours = persistCode(center, "OURCDE23", NOW.plusMinutes(5));
        Long expired = persistCode(center, "OLDCDE23", NOW.minusMinutes(1));
        Long schools = persistCode(school, "SCHCDE23", NOW.plusMinutes(5));

        childService.removeFromRoster(OUR_STAFF, child.getId());

        assertThat(reload(Invitation.class, ours).getStatus()).isEqualTo(InvitationStatus.CANCELLED);
        assertThat(reload(Invitation.class, expired).getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(reload(Invitation.class, schools).getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(em.getEntityManager()
                .createQuery("select i from Invitation i where i.code = 'USEDCD23'", Invitation.class)
                .getSingleResult().getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
    }

    @Test
    void 뺀_아이에게는_초대_코드를_더_발급할_수_없다() {
        childService.removeFromRoster(OUR_STAFF, child.getId());
        em.flush();
        em.clear();

        assertChildNotFound(() -> invitationService.issueFor(OUR_STAFF, child.getId()));
    }

    // ── 인사이트 열람권과 보호자 ────────────────────────────

    @Test
    void 받은_권한과_재료를_낸_권한을_모두_거두고_보호자_권한은_남긴다() {
        grantBoth();
        Long fromSchool = insight(LocalDate.of(2026, 10, 5), school);
        Long fromBoth = insight(LocalDate.of(2026, 10, 12), school, center);
        Long schoolOnly = insight(LocalDate.of(2026, 10, 19), school);
        Long received = grant(fromSchool, school, ReceiverType.ORGANIZATION, center.getId());
        Long fedByUs = grant(fromBoth, school, ReceiverType.ORGANIZATION, school.getId());
        Long unrelated = grant(schoolOnly, school, ReceiverType.ORGANIZATION, school.getId());
        Long toMother = grant(fromBoth, school, ReceiverType.GUARDIAN, motherId);

        childService.removeFromRoster(OUR_STAFF, child.getId());

        assertThat(reload(SharingHistory.class, received)).satisfies(h -> {
            assertThat(h.isActive()).isFalse();
            assertThat(h.getRevokeReason()).isEqualTo(ConsentRevokeReason.ROSTER_REMOVED);
        });
        assertThat(reload(SharingHistory.class, fedByUs).isActive()).isFalse();
        assertThat(reload(SharingHistory.class, unrelated).isActive()).isTrue();
        assertThat(reload(SharingHistory.class, toMother).isActive()).isTrue();
    }

    @Test
    void 보호자_연결은_그대로_남는다() {
        childService.removeFromRoster(OUR_STAFF, child.getId());
        em.flush();
        em.clear();

        assertThat(em.getEntityManager()
                .createQuery("select g from ChildGuardian g where g.childId = :childId", ChildGuardian.class)
                .setParameter("childId", child.getId())
                .getSingleResult().getDeletedAt()).isNull();
    }

    // ── 오류 ─────────────────────────────────────────────

    @Test
    void 이미_뺀_아이를_다시_빼면_404다() {
        childService.removeFromRoster(OUR_STAFF, child.getId());
        em.flush();
        em.clear();

        assertChildNotFound(() -> childService.removeFromRoster(OUR_STAFF, child.getId()));
    }

    @Test
    void 우리_명부에_없거나_없는_아이는_404다() {
        Child elsewhere = em.persistAndFlush(Child.of("이바다", LocalDate.of(2018, 5, 1)));
        em.persistAndFlush(ChildOrganization.of(elsewhere.getId(), school.getId(), "S-2"));

        assertChildNotFound(() -> childService.removeFromRoster(OUR_STAFF, elsewhere.getId()));
        assertChildNotFound(() -> childService.removeFromRoster(OUR_STAFF, 999_999L));
    }

    @Test
    void 삭제된_아이는_404다() {
        em.find(Child.class, child.getId()).delete();
        em.flush();
        em.clear();

        assertChildNotFound(() -> childService.removeFromRoster(OUR_STAFF, child.getId()));
    }

    @Test
    void 기관_소속이_아니면_403이고_아무것도_바뀌지_않는다() {
        given(userService.getOrganizationIdOf("u-parent"))
                .willThrow(new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED));

        assertThatThrownBy(() -> childService.removeFromRoster("u-parent", child.getId()))
                .isInstanceOf(UserException.class);
        assertThat(reload(ChildOrganization.class, ourLinkId).isDeleted()).isFalse();
    }

    // ── 다시 연결 ─────────────────────────────────────────

    /**
     * 빼고 다시 연결해도 거둔 기관 열람권은 살아나지 않는다. Gate 2 대기 글은 근거를 낸 기관의 주간 리포트 동의를
     * 보므로, 다시 연결만 해서는 숨은 채이고 보호자가 동의를 다시 켜야 돌아온다.
     */
    @Test
    void 다시_연결하고_동의해도_예전_수신함_글은_돌아오지_않고_대기_글은_동의_뒤에_돌아온다() {
        grantBoth();
        Long inboxInsight = insight(LocalDate.of(2026, 10, 5), school);
        grant(inboxInsight, school, ReceiverType.ORGANIZATION, center.getId());
        Long pending = insight(LocalDate.of(2026, 10, 12), center);
        assertThat(institutionInboxService.getInbox(OUR_STAFF)).hasSize(1);
        assertThat(insightGate2QueryService.getInsights(OUR_STAFF, "pending")).hasSize(1);

        childService.removeFromRoster(OUR_STAFF, child.getId());
        institutionLinkService.request(MOTHER, child.getId(), CENTER_CODE);
        institutionLinkService.accept(OUR_STAFF, child.getId(), "C-NEW");
        em.flush();
        em.clear();

        assertThat(insightGate2QueryService.getInsights(OUR_STAFF, "pending")).isEmpty();

        consentService.update(MOTHER, child.getId(), center.getId(), ConsentAction.GRANT,
                List.of(ConsentField.DAILY_SUMMARY, ConsentField.WEEKLY_INSIGHT));
        em.flush();
        em.clear();

        assertThat(institutionInboxService.getInbox(OUR_STAFF)).extracting(InboxItemResponse::id)
                .doesNotContain(String.valueOf(inboxInsight));
        assertThatThrownBy(() -> institutionInboxService.getInboxItem(OUR_STAFF, inboxInsight))
                .isInstanceOfSatisfying(SharingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SharingErrorCode.NOT_FOUND));
        assertThat(insightGate2QueryService.getInsights(OUR_STAFF, "pending"))
                .extracting(Gate2InsightResponse::id)
                .containsExactly(String.valueOf(pending));
    }

    // ── 도우미 ───────────────────────────────────────────

    private void grantBoth() {
        consentService.update(MOTHER, child.getId(), center.getId(), ConsentAction.GRANT,
                List.of(ConsentField.DAILY_SUMMARY, ConsentField.WEEKLY_INSIGHT));
        consentService.update(MOTHER, child.getId(), school.getId(), ConsentAction.GRANT,
                List.of(ConsentField.WEEKLY_INSIGHT));
        em.flush();
        em.clear();
    }

    private SharingConsent consentOf(Organization organization) {
        em.flush();
        em.clear();
        return em.getEntityManager()
                .createQuery("select c from SharingConsent c where c.childId = :childId "
                        + "and c.organizationId = :organizationId", SharingConsent.class)
                .setParameter("childId", child.getId())
                .setParameter("organizationId", organization.getId())
                .getSingleResult();
    }

    private Long persistCode(Organization organization, String code, LocalDateTime expiresAt) {
        Long id = em.persistAndFlush(Invitation.issue(child.getId(), organization.getId(), code, expiresAt)).getId();
        em.clear();
        return id;
    }

    private Long insight(LocalDate monday, Organization... sources) {
        String json = "[" + String.join(",",
                java.util.Arrays.stream(sources).map(o -> String.valueOf(o.getId())).toList()) + "]";
        Long id = em.persistAndFlush(InsightResult.of(child.getId(), monday, monday.plusDays(6), 1,
                "급식실 입장 직후", "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.", null,
                "[]", "[]", json, sources.length > 1, null)).getId();
        em.clear();
        return id;
    }

    private Long grant(Long insightId, Organization sender, ReceiverType receiverType, Long receiverId) {
        Long id = em.persistAndFlush(
                SharingHistory.grant(child.getId(), insightId, sender.getId(), receiverType, receiverId)).getId();
        em.clear();
        return id;
    }

    private <T> T reload(Class<T> type, Long id) {
        em.flush();
        em.clear();
        return em.find(type, id);
    }

    private static void assertChildNotFound(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ChildException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChildErrorCode.CHILD_NOT_FOUND));
    }
}
