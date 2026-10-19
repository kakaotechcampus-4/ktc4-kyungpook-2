package com.itda.backend.domain.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChildGuardianTest {

    @Test
    void linkKeepsInvitationThatCreatedIt() {
        ChildGuardian guardian = ChildGuardian.link(1L, 7L, 3L);

        assertThat(guardian.getInvitationId()).isEqualTo(3L);
        assertThat(guardian.getLinkedAt()).isNotNull();
        assertThat(guardian.isDeleted()).isFalse();
    }

    @Test
    void rejectsMissingIds() {
        assertThatThrownBy(() -> ChildGuardian.link(null, 7L, 3L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChildGuardian.link(1L, null, 3L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChildGuardian.link(1L, 7L, null)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 다시 연결되면 해제 권한의 근거도 새 초대로 바뀐다. */
    @Test
    void relinkRestoresWithNewInvitation() {
        ChildGuardian guardian = ChildGuardian.link(1L, 7L, 3L);
        guardian.delete();

        guardian.relink(5L);

        assertThat(guardian.isDeleted()).isFalse();
        assertThat(guardian.getInvitationId()).isEqualTo(5L);
    }

    @Test
    void cannotRelinkActiveLink() {
        ChildGuardian guardian = ChildGuardian.link(1L, 7L, 3L);

        assertThatThrownBy(() -> guardian.relink(5L)).isInstanceOf(IllegalStateException.class);
        assertThat(guardian.getInvitationId()).isEqualTo(3L);
    }
}
