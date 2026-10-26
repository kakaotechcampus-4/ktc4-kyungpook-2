package com.itda.backend.service.invitation;

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
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.consent.SharingConsentStatus;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.invitation.Invitation;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.invitation.ChildGuardianResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.exception.invitation.InvitationErrorCode;
import com.itda.backend.exception.invitation.InvitationException;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.service.child.OrganizationChildGuard;
import com.itda.backend.service.consent.ConsentService;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.insight.InsightAccessRevoker;
import com.itda.backend.service.user.UserService;

/**
 * 보호자 연결 조회·해제. 아이는 우리 기관과 다른 기관에 함께 다니고, 보호자 한 명은 우리 코드로,
 * 다른 한 명은 다른 기관 코드로 연결돼 있다.
 */
@DataJpaTest
@ActiveProfiles("test")
// InsightAccessRevoker 는 진짜를 넣는다 — 연결 해제가 그 보호자의 열람 권한까지 거두는지(#213)
// 이 테스트가 같이 지켜준다.
@Import({ChildGuardianService.class, OrganizationChildGuard.class, ConsentService.class,
        ConsentStateReader.class, InsightAccessRevoker.class, ChildGuardianServiceJpaTest.ClockConfig.class})
class ChildGuardianServiceJpaTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 10, 0);
    private static final String OUR_USER = "u-ours";
    private static final String OTHER_USER = "u-other";
    private static final String THIRD_USER = "u-third";
    private static final String MOTHER = "u-mother";
    private static final String STRANGER = "u-stranger";
    private static final Long OUR_STAFF_ID = 777L;

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private ChildGuardianService childGuardianService;

    @Autowired
    private TestEntityManager em;

    @MockitoBean
    private UserService userService;

    private Organization ours;
    private Organization other;
    private Child child;
    private User mother;
    private User father;

    @BeforeEach
    void setUp() {
        ours = em.persist(OrganizationFixture.center());
        other = em.persist(OrganizationFixture.center());
        Organization third = em.persist(OrganizationFixture.center());
        child = em.persist(Child.of("김하늘", LocalDate.of(2017, 3, 14)));
        em.persist(ChildOrganization.of(child.getId(), ours.getId()));
        em.persist(ChildOrganization.of(child.getId(), other.getId()));
        mother = em.persist(UserFixture.parent("kakao-mother", "김엄마"));
        father = em.persist(UserFixture.parent("kakao-father", "김아빠"));
        User stranger = em.persist(UserFixture.parent("kakao-stranger", "남"));
        link(mother, ours);
        link(father, other);
        em.flush();
        em.clear();

        given(userService.getOrganizationIdOf(OUR_USER)).willReturn(ours.getId());
        given(userService.getOrganizationIdOf(OTHER_USER)).willReturn(other.getId());
        given(userService.getOrganizationIdOf(THIRD_USER)).willReturn(third.getId());
        given(userService.getParentIdOf(MOTHER)).willReturn(mother.getId());
        given(userService.getParentIdOf(STRANGER)).willReturn(stranger.getId());
        given(userService.getUserIdOf(OUR_USER)).willReturn(OUR_STAFF_ID);
    }

    private void consent(User guardian, Organization organization) {
        em.persist(SharingConsent.grant(child.getId(), guardian.getId(), organization.getId(), List.of(ConsentField.DAILY_SUMMARY)));
        em.merge(child).activate();
        em.flush();
        em.clear();
    }

    private List<SharingConsent> consentsOf(User guardian) {
        em.flush();
        em.clear();
        return em.getEntityManager()
                .createQuery("select c from SharingConsent c where c.childId = :childId and c.guardianId = :guardianId",
                        SharingConsent.class)
                .setParameter("childId", child.getId())
                .setParameter("guardianId", guardian.getId())
                .getResultList();
    }

    private void link(User guardian, Organization through) {
        Invitation invitation = Invitation.issue(child.getId(), through.getId(),
                "CD%06d".formatted(guardian.getId() % 1_000_000), NOW.plusMinutes(10));
        em.persist(invitation);
        em.persist(ChildGuardian.link(child.getId(), guardian.getId(), invitation.getId()));
    }

    private ChildGuardian linkOf(User guardian) {
        em.flush();
        em.clear();
        return em.getEntityManager()
                .createQuery("select g from ChildGuardian g where g.childId = :childId and g.userId = :userId",
                        ChildGuardian.class)
                .setParameter("childId", child.getId())
                .setParameter("userId", guardian.getId())
                .getSingleResult();
    }

    @Test
    void 연결된_보호자를_모두_보여주고_우리_코드로_들어온_사람만_표시한다() {
        assertThat(childGuardianService.listGuardians(OUR_USER, child.getId()))
                .extracting(ChildGuardianResponse::userId, ChildGuardianResponse::name,
                        ChildGuardianResponse::linkedByThisOrganization)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(String.valueOf(mother.getId()), "김엄마", true),
                        org.assertj.core.groups.Tuple.tuple(String.valueOf(father.getId()), "김아빠", false));
    }

    @Test
    void 해제된_보호자는_목록에_나오지_않는다() {
        childGuardianService.unlinkByOrganization(OUR_USER, child.getId(), mother.getId());
        em.flush();
        em.clear();

        assertThat(childGuardianService.listGuardians(OUR_USER, child.getId()))
                .extracting(ChildGuardianResponse::userId)
                .containsExactly(String.valueOf(father.getId()));
    }

    @Test
    void 기관은_자기_코드로_연결된_보호자를_해제한다() {
        childGuardianService.unlinkByOrganization(OUR_USER, child.getId(), mother.getId());

        assertThat(linkOf(mother).isDeleted()).isTrue();
    }

    /** 연결은 전역이라, 다른 기관을 통해 들어온 보호자를 끊으면 그 기관의 판단을 뒤집게 된다. */
    @Test
    void 다른_기관_코드로_연결된_보호자는_해제할_수_없다() {
        assertThatThrownBy(() -> childGuardianService.unlinkByOrganization(OUR_USER, child.getId(), father.getId()))
                .isInstanceOfSatisfying(InvitationException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InvitationErrorCode.GUARDIAN_LINKED_BY_OTHER_ORGANIZATION));
        assertThat(linkOf(father).isDeleted()).isFalse();
    }

    @Test
    void 연결되지_않았거나_이미_해제된_보호자는_404다() {
        childGuardianService.unlinkByOrganization(OUR_USER, child.getId(), mother.getId());
        em.flush();
        em.clear();

        assertGuardianNotFound(() -> childGuardianService.unlinkByOrganization(OUR_USER, child.getId(), mother.getId()));
        assertGuardianNotFound(() -> childGuardianService.unlinkByOrganization(OUR_USER, child.getId(), 999_999L));
    }

    @Test
    void 명부에_없는_기관은_조회도_해제도_할_수_없다() {
        assertChildNotFound(() -> childGuardianService.listGuardians(THIRD_USER, child.getId()));
        assertChildNotFound(() -> childGuardianService.unlinkByOrganization(THIRD_USER, child.getId(), mother.getId()));
        assertThat(linkOf(mother).isDeleted()).isFalse();
    }

    @Test
    void 삭제된_아이는_조회도_해제도_할_수_없다() {
        em.find(Child.class, child.getId()).delete();
        em.flush();
        em.clear();

        assertChildNotFound(() -> childGuardianService.listGuardians(OUR_USER, child.getId()));
        assertChildNotFound(() -> childGuardianService.unlinkByOrganization(OUR_USER, child.getId(), mother.getId()));
    }

    @Test
    void 보호자는_자기_연결을_끊을_수_있다() {
        childGuardianService.unlinkBySelf(MOTHER, child.getId());

        assertThat(linkOf(mother).isDeleted()).isTrue();
        assertThat(linkOf(father).isDeleted()).isFalse();
    }

    @Test
    void 연결되지_않은_아이는_보호자가_끊을_수_없다() {
        assertThatThrownBy(() -> childGuardianService.unlinkBySelf(STRANGER, child.getId()))
                .isInstanceOfSatisfying(InvitationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(InvitationErrorCode.CHILD_NOT_LINKED));
    }

    /** 끊긴 사람의 동의로 공유가 계속되면 안 된다. 남은 보호자의 동의는 그대로 두므로 아이는 계속 active 다. */
    @Test
    void 기관이_보호자를_끊으면_그_보호자의_동의만_꺼진다() {
        consent(mother, ours);
        consent(father, ours);

        childGuardianService.unlinkByOrganization(OUR_USER, child.getId(), mother.getId());

        assertThat(consentsOf(mother)).singleElement().satisfies(consent -> {
            assertThat(consent.getStatus()).isEqualTo(SharingConsentStatus.REVOKED);
            assertThat(consent.getRevokeReason()).isEqualTo(ConsentRevokeReason.GUARDIAN_UNLINKED);
            assertThat(consent.getRevokedBy()).isEqualTo(OUR_STAFF_ID);
            assertThat(consent.getRevokedAt()).isEqualTo(NOW);
        });
        assertThat(consentsOf(father)).singleElement()
                .satisfies(consent -> assertThat(consent.isActive()).isTrue());
        assertThat(em.find(Child.class, child.getId()).getStatus()).isEqualTo(ChildStatus.ACTIVE);
    }

    /** 연결 해제는 전역이라 모든 기관에 대한 그 사람의 동의가 꺼진다. 남은 동의가 없으면 아이는 suspended 다. */
    @Test
    void 보호자가_스스로_끊으면_모든_기관의_자기_동의가_꺼진다() {
        consent(mother, ours);
        consent(mother, other);

        childGuardianService.unlinkBySelf(MOTHER, child.getId());

        assertThat(consentsOf(mother)).hasSize(2).allSatisfy(consent -> {
            assertThat(consent.getStatus()).isEqualTo(SharingConsentStatus.REVOKED);
            assertThat(consent.getRevokeReason()).isEqualTo(ConsentRevokeReason.GUARDIAN_UNLINKED);
            assertThat(consent.getRevokedBy()).isEqualTo(mother.getId());
        });
        assertThat(em.find(Child.class, child.getId()).getStatus()).isEqualTo(ChildStatus.SUSPENDED);
    }

    @Test
    void 동의하지_않은_보호자를_끊어도_아이_상태는_바뀌지_않는다() {
        childGuardianService.unlinkBySelf(MOTHER, child.getId());
        em.flush();
        em.clear();

        assertThat(em.find(Child.class, child.getId()).getStatus()).isEqualTo(ChildStatus.PENDING_CONSENT);
    }

    private static void assertChildNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ChildException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ChildErrorCode.CHILD_NOT_FOUND));
    }

    private static void assertGuardianNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(InvitationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(InvitationErrorCode.GUARDIAN_NOT_FOUND));
    }
}
