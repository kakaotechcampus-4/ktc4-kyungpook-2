package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;

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

/**
 * 보호자 연결 조회·해제. 아이는 우리 기관과 다른 기관에 함께 다니고, 보호자 한 명은 우리 코드로,
 * 다른 한 명은 다른 기관 코드로 연결돼 있다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({ChildGuardianService.class, OrganizationChildGuard.class, ChildGuardianServiceJpaTest.ClockConfig.class})
class ChildGuardianServiceJpaTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 10, 0);
    private static final String OUR_USER = "u-ours";
    private static final String OTHER_USER = "u-other";
    private static final String THIRD_USER = "u-third";
    private static final String MOTHER = "u-mother";
    private static final String STRANGER = "u-stranger";

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

    private static void assertChildNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ChildException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ChildErrorCode.CHILD_NOT_FOUND));
    }

    private static void assertGuardianNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(InvitationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(InvitationErrorCode.GUARDIAN_NOT_FOUND));
    }
}
