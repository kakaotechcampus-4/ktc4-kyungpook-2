package com.itda.backend.domain.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class ChildOrganizationRequestTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 10, 0);

    @Test
    void newRequestWaitsForTheOrganization() {
        ChildOrganizationRequest request = ChildOrganizationRequest.request(1L, 10L, 41L);

        assertThat(request.isPending()).isTrue();
        assertThat(request.getRequestedBy()).isEqualTo(41L);
        assertThat(request.getDecidedBy()).isNull();
    }

    @Test
    void acceptAndRejectRecordWhoDecidedAndWhen() {
        ChildOrganizationRequest accepted = ChildOrganizationRequest.request(1L, 10L, 41L);
        ChildOrganizationRequest rejected = ChildOrganizationRequest.request(1L, 10L, 52L);

        accepted.accept(7L, NOW);
        rejected.reject(7L, NOW);

        assertThat(accepted.getStatus()).isEqualTo(ChildOrganizationRequestStatus.ACCEPTED);
        assertThat(accepted.getDecidedBy()).isEqualTo(7L);
        assertThat(accepted.getDecidedAt()).isEqualTo(NOW);
        assertThat(rejected.getStatus()).isEqualTo(ChildOrganizationRequestStatus.REJECTED);
    }

    @Test
    void cancelHasNoDecider() {
        ChildOrganizationRequest request = ChildOrganizationRequest.request(1L, 10L, 41L);

        request.cancel(NOW);

        assertThat(request.getStatus()).isEqualTo(ChildOrganizationRequestStatus.CANCELLED);
        assertThat(request.getDecidedBy()).isNull();
        assertThat(request.getDecidedAt()).isEqualTo(NOW);
    }

    @Test
    void decidedRequestCannotBeDecidedAgain() {
        ChildOrganizationRequest request = ChildOrganizationRequest.request(1L, 10L, 41L);
        request.reject(7L, NOW);

        assertThatThrownBy(() -> request.accept(7L, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.cancel(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void idsAreRequired() {
        assertThatThrownBy(() -> ChildOrganizationRequest.request(null, 10L, 41L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChildOrganizationRequest.request(1L, 10L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
