package com.itda.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UserTest {

    /** 카카오 로그인 직후의 회원. 역할은 가입 API 에서 정해진다. */
    @Test
    void pendingUserHasNoRoleYet() {
        User user = User.pending("k-1", "박지현");

        assertThat(user.getRole()).isNull();
        assertThat(user.getOrganizationId()).isNull();
        assertThat(user.isSignupCompleted()).isFalse();
        assertThat(user.isOrganization()).isFalse();
    }

    @Test
    void organizationSignupFixesRoleAndOrganization() {
        User user = User.pending("k-1", "박지현");

        user.completeSignup(UserRole.ORGANIZATION, 7L);

        assertThat(user.getRole()).isEqualTo(UserRole.ORGANIZATION);
        assertThat(user.getOrganizationId()).isEqualTo(7L);
        assertThat(user.isSignupCompleted()).isTrue();
    }

    @Test
    void parentSignupHasNoOrganization() {
        User user = User.pending("k-1", "김보호");

        user.completeSignup(UserRole.PARENT, null);

        assertThat(user.getRole()).isEqualTo(UserRole.PARENT);
        assertThat(user.getOrganizationId()).isNull();
    }

    /** 역할은 한 번 정해지면 바뀌지 않는다. 서비스가 409 로 먼저 막지만 엔티티도 스스로 지킨다. */
    @Test
    void signupCannotBeCompletedTwice() {
        User user = User.pending("k-1", "박지현");
        user.completeSignup(UserRole.PARENT, null);

        assertThatThrownBy(() -> user.completeSignup(UserRole.ORGANIZATION, 7L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(user.getRole()).isEqualTo(UserRole.PARENT);
    }

    @Test
    void roleIsRequired() {
        User user = User.pending("k-1", "박지현");

        assertThatThrownBy(() -> user.completeSignup(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parentCannotBelongToAnOrganization() {
        User user = User.pending("k-1", "김보호");

        assertThatThrownBy(() -> user.completeSignup(UserRole.PARENT, 7L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 소속 없는 기관 담당자가 생기면 /auth/me 가 institutionId 없는 org 응답을 내보낸다. */
    @Test
    void organizationUserMustBelongToAnOrganization() {
        User user = User.pending("k-1", "박지현");

        assertThatThrownBy(() -> user.completeSignup(UserRole.ORGANIZATION, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
