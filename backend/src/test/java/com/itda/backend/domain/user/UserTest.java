package com.itda.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void pendingUserHasNoRoleYet() {
        User user = User.pending("k-1");

        assertThat(user.getRole()).isNull();
        assertThat(user.getOrganizationId()).isNull();
        assertThat(user.isSignupCompleted()).isFalse();
        assertThat(user.isOrganization()).isFalse();
    }

    @Test
    void organizationSignupFixesRoleAndOrganization() {
        User user = User.pending("k-1");

        user.completeSignup(UserRole.ORGANIZATION, 7L);

        assertThat(user.getRole()).isEqualTo(UserRole.ORGANIZATION);
        assertThat(user.getOrganizationId()).isEqualTo(7L);
        assertThat(user.isSignupCompleted()).isTrue();
    }

    @Test
    void parentSignupHasNoOrganization() {
        User user = User.pending("k-1");

        user.completeSignup(UserRole.PARENT, null);

        assertThat(user.getRole()).isEqualTo(UserRole.PARENT);
        assertThat(user.getOrganizationId()).isNull();
    }

    @Test
    void signupCannotBeCompletedTwice() {
        User user = User.pending("k-1");
        user.completeSignup(UserRole.PARENT, null);

        assertThatThrownBy(() -> user.completeSignup(UserRole.ORGANIZATION, 7L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(user.getRole()).isEqualTo(UserRole.PARENT);
    }

    @Test
    void roleIsRequired() {
        User user = User.pending("k-1");

        assertThatThrownBy(() -> user.completeSignup(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parentCannotBelongToAnOrganization() {
        User user = User.pending("k-1");

        assertThatThrownBy(() -> user.completeSignup(UserRole.PARENT, 7L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void organizationUserMustBelongToAnOrganization() {
        User user = User.pending("k-1");

        assertThatThrownBy(() -> user.completeSignup(UserRole.ORGANIZATION, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
