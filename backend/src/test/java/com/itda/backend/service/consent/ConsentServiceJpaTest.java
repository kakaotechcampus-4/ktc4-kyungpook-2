package com.itda.backend.service.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
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
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.consent.SharingConsentStatus;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.invitation.Invitation;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.consent.ConsentPreviewResponse;
import com.itda.backend.dto.response.consent.ConsentResponse;
import com.itda.backend.exception.consent.ConsentErrorCode;
import com.itda.backend.exception.consent.ConsentException;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.service.insight.InsightAccessRevoker;
import com.itda.backend.service.user.UserService;

/**
 * 동의 미리보기·부여·철회가 실제 DB 행과 아동 상태를 어떻게 바꾸는지 본다.
 * 아이는 센터(ours)와 학교(school)에 함께 다니고, 엄마·아빠가 연결돼 있다. 명부에 없는 기관(outside)도 있다.
 */
@DataJpaTest
@ActiveProfiles("test")
// InsightAccessRevoker 는 가짜가 아니라 진짜를 넣는다 — 동의 철회가 열람 권한까지 거두는지(#213)
// 이 테스트가 같이 지켜준다. 가짜면 배선이 끊겨도 초록이다.
@Import({ConsentService.class, ConsentStateReader.class, InsightAccessRevoker.class,
        ConsentServiceJpaTest.ClockConfig.class})
class ConsentServiceJpaTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 10, 0);
    private static final String MOTHER = "u-mother";
    private static final String FATHER = "u-father";
    private static final String STRANGER = "u-stranger";
    private static final String STAFF = "u-staff";
    private static final List<ConsentField> BOTH = List.of(ConsentField.DAILY_SUMMARY, ConsentField.WEEKLY_INSIGHT);
    private static final List<ConsentField> DAILY = List.of(ConsentField.DAILY_SUMMARY);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private ConsentService consentService;

    @Autowired
    private ChildRepository childRepository;

    @Autowired
    private TestEntityManager em;

    @MockitoBean
    private UserService userService;

    private Organization ours;
    private Organization school;
    private Organization outside;
    private Child child;
    private User mother;
    private User father;

    @BeforeEach
    void setUp() {
        ours = em.persist(OrganizationFixture.center());
        school = em.persist(Organization.of("○○초등학교", OrganizationType.SCHOOL, OrganizationFixture.nextBusinessNumber()));
        outside = em.persist(OrganizationFixture.center());
        child = em.persist(Child.of("김하늘", LocalDate.of(2017, 3, 14)));
        em.persist(ChildOrganization.of(child.getId(), ours.getId()));
        em.persist(ChildOrganization.of(child.getId(), school.getId()));
        mother = em.persist(UserFixture.parent("kakao-mother", "김엄마"));
        father = em.persist(UserFixture.parent("kakao-father", "김아빠"));
        User stranger = em.persist(UserFixture.parent("kakao-stranger", "남"));
        link(mother);
        link(father);
        em.flush();
        em.clear();

        given(userService.getParentIdOf(MOTHER)).willReturn(mother.getId());
        given(userService.getParentIdOf(FATHER)).willReturn(father.getId());
        given(userService.getParentIdOf(STRANGER)).willReturn(stranger.getId());
        given(userService.getParentIdOf(STAFF)).willThrow(new UserException(UserErrorCode.PARENT_REQUIRED));
    }

    private void link(User guardian) {
        Invitation invitation = Invitation.issue(child.getId(), ours.getId(),
                "CN%06d".formatted(guardian.getId() % 1_000_000), NOW.plusMinutes(10));
        em.persist(invitation);
        em.persist(ChildGuardian.link(child.getId(), guardian.getId(), invitation.getId()));
    }

    private ConsentResponse grant(String user, Organization organization, List<ConsentField> fields) {
        ConsentResponse response = consentService.update(user, child.getId(), organization.getId(), ConsentAction.GRANT, fields);
        em.flush();
        em.clear();
        return response;
    }

    private ConsentResponse revoke(String user, Organization organization) {
        ConsentResponse response = consentService.update(user, child.getId(), organization.getId(), ConsentAction.REVOKE, null);
        em.flush();
        em.clear();
        return response;
    }

    private List<SharingConsent> consentsAt(Organization organization) {
        em.flush();
        em.clear();
        return em.getEntityManager()
                .createQuery("select c from SharingConsent c where c.childId = :childId and c.organizationId = :organizationId "
                        + "order by c.id", SharingConsent.class)
                .setParameter("childId", child.getId())
                .setParameter("organizationId", organization.getId())
                .getResultList();
    }

    private ChildStatus childStatus() {
        em.flush();
        em.clear();
        return em.find(Child.class, child.getId()).getStatus();
    }

    // --- grant ---

    /** 동의는 기관별이다. 센터에 동의하면 센터 명부에만 들어가고, 학교 명부에는 들어가지 않는다. */
    @Test
    void 첫_동의로_아이가_active_가_되고_그_기관_명부에만_들어간다() {
        ConsentResponse response = grant(MOTHER, ours, BOTH);

        assertThat(response.childStatus()).isEqualTo(ChildStatus.ACTIVE);
        assertThat(response.consent()).isEqualTo(ConsentState.GRANTED);
        assertThat(response.allowedFields()).containsExactlyElementsOf(BOTH);
        assertThat(consentsAt(ours)).singleElement().satisfies(consent -> {
            assertThat(consent.getGuardianId()).isEqualTo(mother.getId());
            assertThat(consent.getScope()).containsExactlyElementsOf(BOTH);
            assertThat(consent.isActive()).isTrue();
        });
        assertThat(childStatus()).isEqualTo(ChildStatus.ACTIVE);
        assertThat(childRepository.findConsentedByOrganizationId(ours.getId())).extracting(Child::getId)
                .containsExactly(child.getId());
        assertThat(childRepository.findConsentedByOrganizationId(school.getId())).isEmpty();
    }

    /** 같은 보호자가 다시 동의하면 새 행 없이 항목만 바뀐다. 같은 요청을 여러 번 보내도 결과가 같다. */
    @Test
    void 같은_보호자가_다시_동의하면_항목만_바뀐다() {
        grant(MOTHER, ours, DAILY);
        grant(MOTHER, ours, BOTH);
        ConsentResponse again = grant(MOTHER, ours, BOTH);

        assertThat(again.allowedFields()).containsExactlyElementsOf(BOTH);
        assertThat(consentsAt(ours)).singleElement()
                .satisfies(consent -> assertThat(consent.getScope()).containsExactlyElementsOf(BOTH));
    }

    /** 공유 항목은 기관 단위로 하나다. 마지막으로 동의한 보호자의 항목으로 그 기관의 ACTIVE 행을 모두 맞춘다. */
    @Test
    void 두_번째_보호자가_동의하면_자기_행이_생기고_기관의_항목이_하나로_맞춰진다() {
        grant(MOTHER, ours, BOTH);
        ConsentResponse response = grant(FATHER, ours, DAILY);

        assertThat(response.allowedFields()).containsExactlyElementsOf(DAILY);
        assertThat(consentsAt(ours)).hasSize(2).allSatisfy(consent -> {
            assertThat(consent.isActive()).isTrue();
            assertThat(consent.getScope()).containsExactlyElementsOf(DAILY);
        }).extracting(SharingConsent::getGuardianId).containsExactly(mother.getId(), father.getId());
    }

    @Test
    void 같은_항목이_두_번_오면_하나로_합쳐_저장한다() {
        grant(MOTHER, ours, List.of(ConsentField.WEEKLY_INSIGHT, ConsentField.DAILY_SUMMARY, ConsentField.WEEKLY_INSIGHT));

        assertThat(consentsAt(ours)).singleElement()
                .satisfies(consent -> assertThat(consent.getScope()).containsExactlyElementsOf(BOTH));
    }

    // --- revoke ---

    /** 끄는 쪽을 우선한다. 아빠가 철회하면 엄마의 동의까지 그 기관 동의가 모두 꺼진다. */
    @Test
    void 한_보호자가_철회하면_그_기관의_모든_동의가_꺼진다() {
        grant(MOTHER, ours, BOTH);
        grant(FATHER, ours, BOTH);

        ConsentResponse response = revoke(FATHER, ours);

        assertThat(response.childStatus()).isEqualTo(ChildStatus.SUSPENDED);
        assertThat(response.consent()).isEqualTo(ConsentState.REVOKED);
        assertThat(response.allowedFields()).isEmpty();
        assertThat(consentsAt(ours)).hasSize(2).allSatisfy(consent -> {
            assertThat(consent.getStatus()).isEqualTo(SharingConsentStatus.REVOKED);
            assertThat(consent.getRevokeReason()).isEqualTo(ConsentRevokeReason.GUARDIAN_REVOKED);
            assertThat(consent.getRevokedBy()).isEqualTo(father.getId());
            assertThat(consent.getRevokedAt()).isEqualTo(NOW);
        });
        assertThat(childStatus()).isEqualTo(ChildStatus.SUSPENDED);
        assertThat(childRepository.findConsentedByOrganizationId(ours.getId())).isEmpty();
    }

    /** 다른 기관 동의가 남아 있으면 아이는 계속 active 다. */
    @Test
    void 다른_기관_동의가_남으면_아이는_active_로_남는다() {
        grant(MOTHER, ours, BOTH);
        grant(MOTHER, school, DAILY);

        ConsentResponse response = revoke(MOTHER, ours);

        assertThat(response.childStatus()).isEqualTo(ChildStatus.ACTIVE);
        assertThat(response.consent()).isEqualTo(ConsentState.REVOKED);
        assertThat(childRepository.findConsentedByOrganizationId(school.getId())).extracting(Child::getId)
                .containsExactly(child.getId());
    }

    @Test
    void 끌_동의가_없으면_아무것도_바꾸지_않고_현재_상태를_돌려준다() {
        ConsentResponse response = revoke(MOTHER, ours);

        assertThat(response.childStatus()).isEqualTo(ChildStatus.PENDING_CONSENT);
        assertThat(response.consent()).isEqualTo(ConsentState.NOT_GRANTED);
        assertThat(response.allowedFields()).isEmpty();
        assertThat(consentsAt(ours)).isEmpty();
        assertThat(childStatus()).isEqualTo(ChildStatus.PENDING_CONSENT);
    }

    /** 철회 뒤 다시 동의하면 새 행이 생긴다. 철회 이력은 그대로 남는다. */
    @Test
    void 철회_뒤_다시_동의하면_새_행으로_켜진다() {
        grant(MOTHER, ours, BOTH);
        revoke(MOTHER, ours);

        ConsentResponse response = grant(MOTHER, ours, DAILY);

        assertThat(response.childStatus()).isEqualTo(ChildStatus.ACTIVE);
        assertThat(consentsAt(ours)).extracting(SharingConsent::getStatus)
                .containsExactly(SharingConsentStatus.REVOKED, SharingConsentStatus.ACTIVE);
    }

    // --- 거절 경로 ---

    @Test
    void 연결되지_않은_아이에는_동의할_수_없다() {
        assertForbidden(() -> grant(STRANGER, ours, BOTH));
        assertForbidden(() -> revoke(STRANGER, ours));
        assertForbidden(() -> consentService.preview(STRANGER, child.getId(), ours.getId()));
        assertThat(consentsAt(ours)).isEmpty();
    }

    @Test
    void 연결을_끊은_보호자는_동의할_수_없다() {
        em.getEntityManager()
                .createQuery("select g from ChildGuardian g where g.userId = :userId", ChildGuardian.class)
                .setParameter("userId", mother.getId())
                .getSingleResult()
                .delete();
        em.flush();
        em.clear();

        assertForbidden(() -> grant(MOTHER, ours, BOTH));
    }

    @Test
    void 기관_담당자는_동의할_수_없다() {
        assertThatThrownBy(() -> grant(STAFF, ours, BOTH))
                .isInstanceOfSatisfying(UserException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.PARENT_REQUIRED));
    }

    @Test
    void 명부에_없는_기관에는_동의할_수_없다() {
        assertTargetNotFound(() -> grant(MOTHER, outside, BOTH));
        assertTargetNotFound(() -> consentService.preview(MOTHER, child.getId(), outside.getId()));
        assertTargetNotFound(() -> consentService.preview(MOTHER, child.getId(), 999_999L));
    }

    @Test
    void 삭제된_아이에는_동의할_수_없다() {
        em.find(Child.class, child.getId()).delete();
        em.flush();
        em.clear();

        assertTargetNotFound(() -> grant(MOTHER, ours, BOTH));
        assertTargetNotFound(() -> consentService.preview(MOTHER, child.getId(), ours.getId()));
    }

    /** 요청 검증을 거치지 않고 들어와도 엔티티까지 내려가 500 이 되지 않고 400 으로 끝난다. */
    @Test
    void 빈_항목이나_비어_있는_값이_섞인_동의는_400() {
        assertInvalid(() -> grant(MOTHER, ours, null));
        assertInvalid(() -> grant(MOTHER, ours, List.of()));
        assertInvalid(() -> grant(MOTHER, ours, Arrays.asList(ConsentField.DAILY_SUMMARY, null)));
        assertThat(consentsAt(ours)).isEmpty();
    }

    // --- preview ---

    @Test
    void 미리보기는_아이와_기관과_공유_범위를_돌려준다() {
        ConsentPreviewResponse preview = consentService.preview(MOTHER, child.getId(), school.getId());

        assertThat(preview.child().id()).isEqualTo(String.valueOf(child.getId()));
        assertThat(preview.child().name()).isEqualTo("김하늘");
        assertThat(preview.child().birthDate()).isEqualTo("2017-03-14");
        assertThat(preview.institution().id()).isEqualTo(String.valueOf(school.getId()));
        assertThat(preview.institution().type()).isEqualTo("school");
        assertThat(preview.institution().verified()).isFalse();
        assertThat(preview.documentUrl()).isNull();
        assertThat(preview.sharedFields()).containsExactly("출결 시간", "활동 요약", "알레르기 정보");
        assertThat(preview.notSharedFields()).containsExactly("사진과 영상", "의료 기록");
        assertThat(preview.allowedFields()).containsExactlyElementsOf(BOTH);
    }

    /** 다른 보호자가 이미 동의한 기관도 "동의할 것이 없다"로 본다. 철회된 뒤에는 다시 보인다. */
    @Test
    void 이미_동의한_기관은_미리보기가_404다() {
        grant(FATHER, ours, BOTH);

        assertTargetNotFound(() -> consentService.preview(MOTHER, child.getId(), ours.getId()));

        revoke(FATHER, ours);
        assertThat(consentService.preview(MOTHER, child.getId(), ours.getId()).institution().type()).isEqualTo("center");
    }

    private static void assertForbidden(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ConsentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ConsentErrorCode.CHILD_NOT_LINKED));
    }

    private static void assertTargetNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ConsentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ConsentErrorCode.CONSENT_TARGET_NOT_FOUND));
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ConsentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ConsentErrorCode.INVALID_CONSENT_REQUEST));
    }
}
