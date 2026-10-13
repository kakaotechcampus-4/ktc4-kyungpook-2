package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.invitation.Invitation;
import com.itda.backend.domain.invitation.InvitationStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.invitation.AcceptedInvitationResponse;
import com.itda.backend.dto.response.invitation.IssuedInvitationResponse;
import com.itda.backend.dto.response.invitation.LiveInvitationResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.exception.invitation.InvitationErrorCode;
import com.itda.backend.exception.invitation.InvitationException;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;

/** 초대 코드 발급·조회·취소·수락이 실제 DB 행을 어떻게 남기는지 확인한다. 시각은 테스트가 정한다. */
@DataJpaTest
@ActiveProfiles("test")
@Import({InvitationService.class, OrganizationChildGuard.class, InviteCodeGenerator.class,
        InvitationServiceJpaTest.ClockConfig.class})
class InvitationServiceJpaTest {

    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 10, 12, 10, 0);
    private static final String OUR_USER = "u-ours";
    private static final String OTHER_USER = "u-other";
    private static final String PARENT = "u-parent";
    private static final String ORGANIZATION_AS_PARENT = "u-org-as-parent";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(ISSUED_AT);
        }
    }

    @Autowired
    private InvitationService invitationService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TestEntityManager em;

    @MockitoBean
    private UserService userService;

    private Organization ours;
    private Organization other;
    private Child child;
    private User parent;

    @BeforeEach
    void setUp() {
        clock.setTo(ISSUED_AT);
        ours = em.persist(OrganizationFixture.center());
        other = em.persist(OrganizationFixture.center());
        child = em.persist(Child.of("김하늘", LocalDate.of(2017, 3, 14)));
        em.persist(ChildOrganization.of(child.getId(), ours.getId()));
        parent = em.persist(UserFixture.parent("kakao-parent", "김보호"));

        given(userService.getOrganizationIdOf(OUR_USER)).willReturn(ours.getId());
        given(userService.getOrganizationIdOf(OTHER_USER)).willReturn(other.getId());
        given(userService.getParentIdOf(PARENT)).willReturn(parent.getId());
        given(userService.getParentIdOf(ORGANIZATION_AS_PARENT))
                .willThrow(new UserException(UserErrorCode.PARENT_REQUIRED));
    }

    private IssuedInvitationResponse issue() {
        IssuedInvitationResponse issued = invitationService.issueFor(OUR_USER, child.getId());
        em.flush();
        em.clear();
        return issued;
    }

    private Invitation stored(IssuedInvitationResponse issued) {
        em.flush();
        em.clear();
        return em.find(Invitation.class, Long.valueOf(issued.id()));
    }

    private List<ChildGuardian> guardiansOf(Long childId) {
        em.flush();
        em.clear();
        return em.getEntityManager()
                .createQuery("select g from ChildGuardian g where g.childId = :childId", ChildGuardian.class)
                .setParameter("childId", childId)
                .getResultList();
    }

    private static String hyphenatedLowercase(String code) {
        return (code.substring(0, 4) + "-" + code.substring(4)).toLowerCase();
    }

    // --- 발급 ---

    @Test
    void 발급한_코드는_8자리이고_10분_뒤_만료된다() {
        IssuedInvitationResponse issued = issue();

        Invitation invitation = stored(issued);
        assertThat(issued.code()).hasSize(8).isEqualTo(invitation.getCode());
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitation.getExpiresAt()).isEqualTo(ISSUED_AT.plusMinutes(10));
        assertThat(issued.expiresAt().toLocalDateTime()).isEqualTo(ISSUED_AT.plusMinutes(10));
        assertThat(issued.expiresAt().getOffset()).isEqualTo(clock.getZone().getRules().getOffset(ISSUED_AT));
    }

    @Test
    void 한_아이에_쓸_수_있는_코드를_여러_개_발급할_수_있다() {
        IssuedInvitationResponse first = issue();
        IssuedInvitationResponse second = issue();

        assertThat(first.code()).isNotEqualTo(second.code());
        assertThat(invitationService.listLive(OUR_USER, child.getId()))
                .extracting(LiveInvitationResponse::id)
                .containsExactly(second.id(), first.id());
    }

    @Test
    void 다른_기관_아이에게는_발급_조회_취소를_할_수_없다() {
        IssuedInvitationResponse issued = issue();

        assertChildNotFound(() -> invitationService.issueFor(OTHER_USER, child.getId()));
        assertChildNotFound(() -> invitationService.listLive(OTHER_USER, child.getId()));
        assertChildNotFound(() -> invitationService.cancel(OTHER_USER, child.getId(), Long.valueOf(issued.id())));
        assertThat(stored(issued).getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void 삭제된_아이와_명부에서_빠진_아이는_찾을_수_없다() {
        Child deleted = em.persist(Child.of("삭제아동", LocalDate.of(2018, 1, 1)));
        em.persist(ChildOrganization.of(deleted.getId(), ours.getId()));
        deleted.delete();
        Child removed = em.persist(Child.of("제외아동", LocalDate.of(2018, 1, 1)));
        ChildOrganization removedLink = em.persist(ChildOrganization.of(removed.getId(), ours.getId()));
        removedLink.delete();
        em.flush();

        assertChildNotFound(() -> invitationService.issueFor(OUR_USER, deleted.getId()));
        assertChildNotFound(() -> invitationService.issueFor(OUR_USER, removed.getId()));
        assertChildNotFound(() -> invitationService.issueFor(OUR_USER, 999_999L));
    }

    @Test
    void 기관_소속이_아니면_발급할_수_없다() {
        given(userService.getOrganizationIdOf(PARENT))
                .willThrow(new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED));

        assertThatThrownBy(() -> invitationService.issueFor(PARENT, child.getId()))
                .isInstanceOfSatisfying(UserException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.ORGANIZATION_NOT_ASSIGNED));
    }

    // --- 목록·취소 ---

    @Test
    void 목록에는_수락_취소_만료된_코드가_빠진다() {
        IssuedInvitationResponse accepted = issue();
        IssuedInvitationResponse cancelled = issue();
        clock.advance(Duration.ofMinutes(5));
        IssuedInvitationResponse live = issue();
        invitationService.accept(PARENT, accepted.code());
        invitationService.cancel(OUR_USER, child.getId(), Long.valueOf(cancelled.id()));
        em.flush();
        em.clear();

        assertThat(invitationService.listLive(OUR_USER, child.getId()))
                .extracting(LiveInvitationResponse::id)
                .containsExactly(live.id());

        clock.advance(Duration.ofMinutes(10));
        assertThat(invitationService.listLive(OUR_USER, child.getId())).isEmpty();
    }

    @Test
    void 취소한_코드는_수락할_수_없다() {
        IssuedInvitationResponse issued = issue();

        invitationService.cancel(OUR_USER, child.getId(), Long.valueOf(issued.id()));

        assertThat(stored(issued).getStatus()).isEqualTo(InvitationStatus.CANCELLED);
        assertInvalidCode(() -> invitationService.accept(PARENT, issued.code()));
    }

    @Test
    void 만료되거나_이미_쓴_코드는_취소할_것이_없다() {
        IssuedInvitationResponse used = issue();
        invitationService.accept(PARENT, used.code());
        IssuedInvitationResponse expired = issue();
        clock.advance(Duration.ofMinutes(10));

        assertInvitationNotFound(() -> invitationService.cancel(OUR_USER, child.getId(), Long.valueOf(used.id())));
        assertInvitationNotFound(() -> invitationService.cancel(OUR_USER, child.getId(), Long.valueOf(expired.id())));
    }

    @Test
    void 다른_아이의_초대는_그_아이_경로로_취소할_수_없다() {
        Child sibling = em.persist(Child.of("김바다", LocalDate.of(2019, 5, 5)));
        em.persist(ChildOrganization.of(sibling.getId(), ours.getId()));
        IssuedInvitationResponse issued = issue();

        assertInvitationNotFound(() -> invitationService.cancel(OUR_USER, sibling.getId(), Long.valueOf(issued.id())));
        assertThat(stored(issued).getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    // --- 수락 ---

    @Test
    void 하이픈과_소문자를_섞어_입력해도_연결된다() {
        IssuedInvitationResponse issued = issue();

        AcceptedInvitationResponse response = invitationService.accept(PARENT, hyphenatedLowercase(issued.code()));

        assertThat(response.childId()).isEqualTo(String.valueOf(child.getId()));
        assertThat(response.childName()).isEqualTo("김하늘");
        assertThat(response.organizationId()).isEqualTo(String.valueOf(ours.getId()));
        assertThat(response.organizationName()).isEqualTo(ours.getName());
        assertThat(response.childStatus()).isEqualTo(ChildStatus.PENDING_CONSENT);
        Invitation invitation = stored(issued);
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(invitation.getAcceptedUserId()).isEqualTo(parent.getId());
        assertThat(guardiansOf(child.getId())).singleElement().satisfies(guardian -> {
            assertThat(guardian.getUserId()).isEqualTo(parent.getId());
            assertThat(guardian.getInvitationId()).isEqualTo(invitation.getId());
            assertThat(guardian.isDeleted()).isFalse();
        });
    }

    /** 연결은 동의가 아니다. 수락은 아동 상태를 바꾸지 않고 실제 현재 상태를 돌려준다. */
    @Test
    void 수락은_아동_상태를_바꾸지_않고_실제_상태를_돌려준다() {
        em.getEntityManager()
                .createQuery("update Child c set c.status = com.itda.backend.domain.child.ChildStatus.ACTIVE where c.id = :id")
                .setParameter("id", child.getId())
                .executeUpdate();
        IssuedInvitationResponse issued = issue();

        AcceptedInvitationResponse response = invitationService.accept(PARENT, issued.code());

        assertThat(response.childStatus()).isEqualTo(ChildStatus.ACTIVE);
        em.flush();
        em.clear();
        assertThat(em.find(Child.class, child.getId()).getStatus()).isEqualTo(ChildStatus.ACTIVE);
    }

    @Test
    void 만료_직전까지만_수락할_수_있다() {
        IssuedInvitationResponse justInTime = issue();
        IssuedInvitationResponse tooLate = issue();
        clock.setTo(ISSUED_AT.plusMinutes(10).minusSeconds(1));

        invitationService.accept(PARENT, justInTime.code());

        clock.setTo(ISSUED_AT.plusMinutes(10));
        assertInvalidCode(() -> invitationService.accept(PARENT, tooLate.code()));
        assertThat(stored(tooLate).getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void 한_번_쓴_코드는_다시_쓸_수_없다() {
        IssuedInvitationResponse issued = issue();
        User otherParent = em.persist(UserFixture.parent("kakao-other-parent", "이보호"));
        given(userService.getParentIdOf("u-other-parent")).willReturn(otherParent.getId());
        invitationService.accept(PARENT, issued.code());

        assertInvalidCode(() -> invitationService.accept("u-other-parent", issued.code()));
        assertThat(guardiansOf(child.getId())).singleElement()
                .satisfies(guardian -> assertThat(guardian.getUserId()).isEqualTo(parent.getId()));
    }

    @Test
    void 형식이_틀리거나_없는_코드는_같은_400이다() {
        assertInvalidCode(() -> invitationService.accept(PARENT, "short"));
        assertInvalidCode(() -> invitationService.accept(PARENT, "ZZZZZZZZ"));
        assertInvalidCode(() -> invitationService.accept(PARENT, null));
    }

    @Test
    void 이미_연결된_보호자가_다른_코드를_넣으면_409이고_코드는_남는다() {
        IssuedInvitationResponse first = issue();
        IssuedInvitationResponse second = issue();
        invitationService.accept(PARENT, first.code());
        em.flush();
        em.clear();

        assertThatThrownBy(() -> invitationService.accept(PARENT, second.code()))
                .isInstanceOfSatisfying(InvitationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(InvitationErrorCode.LINK_ALREADY_EXISTS));
        assertThat(stored(second).getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void 해제된_보호자는_새_코드로_다시_연결되고_연결_출처가_바뀐다() {
        IssuedInvitationResponse first = issue();
        invitationService.accept(PARENT, first.code());
        em.flush();
        em.clear();
        ChildGuardian linked = guardiansOf(child.getId()).get(0);
        linked.delete();
        em.merge(linked);
        IssuedInvitationResponse second = issue();

        invitationService.accept(PARENT, second.code());

        assertThat(guardiansOf(child.getId())).singleElement().satisfies(guardian -> {
            assertThat(guardian.isDeleted()).isFalse();
            assertThat(guardian.getInvitationId()).isEqualTo(Long.valueOf(second.id()));
        });
    }

    @Test
    void 아이가_삭제되거나_발급_기관_명부에서_빠지면_수락할_수_없다() {
        IssuedInvitationResponse forDeleted = issue();
        Child removedChild = em.persist(Child.of("김바다", LocalDate.of(2019, 5, 5)));
        ChildOrganization removedLink = em.persist(ChildOrganization.of(removedChild.getId(), ours.getId()));
        IssuedInvitationResponse forRemoved = invitationService.issueFor(OUR_USER, removedChild.getId());
        em.find(Child.class, child.getId()).delete();
        em.find(ChildOrganization.class, removedLink.getId()).delete();
        em.flush();
        em.clear();

        assertInvalidCode(() -> invitationService.accept(PARENT, forDeleted.code()));
        assertInvalidCode(() -> invitationService.accept(PARENT, forRemoved.code()));
        assertThat(stored(forDeleted).getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(guardiansOf(removedChild.getId())).isEmpty();
    }

    @Test
    void 보호자가_아니면_수락할_수_없다() {
        IssuedInvitationResponse issued = issue();

        assertThatThrownBy(() -> invitationService.accept(ORGANIZATION_AS_PARENT, issued.code()))
                .isInstanceOfSatisfying(UserException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.PARENT_REQUIRED));
        assertThat(stored(issued).getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    private static void assertChildNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ChildException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ChildErrorCode.CHILD_NOT_FOUND));
    }

    private static void assertInvalidCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(InvitationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(InvitationErrorCode.INVALID_INVITE_CODE));
    }

    private static void assertInvitationNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(InvitationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(InvitationErrorCode.INVITATION_NOT_FOUND));
    }
}
