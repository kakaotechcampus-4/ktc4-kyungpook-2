package com.itda.backend.domain.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class InvitationTest {

    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 10, 12, 10, 10);

    private static Invitation pending() {
        return Invitation.issue(1L, 10L, "K7P3QX9M", EXPIRES_AT);
    }

    @Test
    void issuedInvitationIsPendingWithoutAcceptedUser() {
        Invitation invitation = pending();

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitation.getAcceptedUserId()).isNull();
    }

    @Test
    void rejectsMissingIdsOrMalformedCode() {
        assertThatThrownBy(() -> Invitation.issue(null, 10L, "K7P3QX9M", EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Invitation.issue(1L, null, "K7P3QX9M", EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Invitation.issue(1L, 10L, "K7P3QX", EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Invitation.issue(1L, 10L, "K7P3QX9M", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 만료 시각과 같은 순간은 이미 만료다. */
    @Test
    void usableOnlyBeforeExpiry() {
        Invitation invitation = pending();

        assertThat(invitation.isUsable(EXPIRES_AT.minusNanos(1))).isTrue();
        assertThat(invitation.isUsable(EXPIRES_AT)).isFalse();
        assertThat(invitation.isUsable(EXPIRES_AT.plusSeconds(1))).isFalse();
    }

    @Test
    void acceptRecordsUserAndCannotBeUsedAgain() {
        Invitation invitation = pending();
        LocalDateTime now = EXPIRES_AT.minusMinutes(1);

        invitation.accept(7L, now);

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(invitation.getAcceptedUserId()).isEqualTo(7L);
        assertThat(invitation.isUsable(now)).isFalse();
        assertThatThrownBy(() -> invitation.accept(8L, now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotAcceptExpiredInvitation() {
        Invitation invitation = pending();

        assertThatThrownBy(() -> invitation.accept(7L, EXPIRES_AT)).isInstanceOf(IllegalStateException.class);
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void cancelledInvitationCannotBeAcceptedOrCancelledAgain() {
        Invitation invitation = pending();
        LocalDateTime now = EXPIRES_AT.minusMinutes(1);

        invitation.cancel(now);

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.CANCELLED);
        assertThatThrownBy(() -> invitation.accept(7L, now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> invitation.cancel(now)).isInstanceOf(IllegalStateException.class);
    }
}
