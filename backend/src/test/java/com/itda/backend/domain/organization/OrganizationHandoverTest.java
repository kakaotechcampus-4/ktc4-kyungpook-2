package com.itda.backend.domain.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class OrganizationHandoverTest {

    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 11, 10, 6, 0);

    private static OrganizationHandover pending() {
        return OrganizationHandover.issue(10L, 3L, "K7P3QX9M", EXPIRES_AT);
    }

    @Test
    void 발급한_코드는_받은_사람이_없는_대기_상태다() {
        OrganizationHandover handover = pending();

        assertThat(handover.getStatus()).isEqualTo(OrganizationHandoverStatus.PENDING);
        assertThat(handover.getOrganizationId()).isEqualTo(10L);
        assertThat(handover.getIssuedBy()).isEqualTo(3L);
        assertThat(handover.getAcceptedUserId()).isNull();
    }

    @Test
    void 기관_발급자_코드_만료가_빠지거나_코드가_8자가_아니면_만들지_않는다() {
        assertThatThrownBy(() -> OrganizationHandover.issue(null, 3L, "K7P3QX9M", EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrganizationHandover.issue(10L, null, "K7P3QX9M", EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrganizationHandover.issue(10L, 3L, "K7P3QX", EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrganizationHandover.issue(10L, 3L, "K7P3QX9M", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 만료_전에만_쓸_수_있다() {
        OrganizationHandover handover = pending();

        assertThat(handover.isUsable(EXPIRES_AT.minusNanos(1))).isTrue();
        assertThat(handover.isUsable(EXPIRES_AT)).isFalse();
    }

    @Test
    void 받으면_받은_사람을_남기고_다시_쓸_수_없다() {
        OrganizationHandover handover = pending();
        LocalDateTime now = EXPIRES_AT.minusHours(1);

        handover.accept(7L, now);

        assertThat(handover.getStatus()).isEqualTo(OrganizationHandoverStatus.ACCEPTED);
        assertThat(handover.getAcceptedUserId()).isEqualTo(7L);
        assertThat(handover.getAcceptedAt()).isEqualTo(now);
        assertThatThrownBy(() -> handover.accept(8L, now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 만료되거나_취소된_코드는_받을_수_없다() {
        OrganizationHandover expired = pending();
        assertThatThrownBy(() -> expired.accept(7L, EXPIRES_AT)).isInstanceOf(IllegalStateException.class);

        OrganizationHandover cancelled = pending();
        cancelled.cancel(EXPIRES_AT.minusHours(1));
        assertThat(cancelled.getStatus()).isEqualTo(OrganizationHandoverStatus.CANCELLED);
        assertThatThrownBy(() -> cancelled.accept(7L, EXPIRES_AT.minusHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 발급한_담당자는_자기_코드를_받을_수_없다() {
        OrganizationHandover handover = pending();

        assertThatThrownBy(() -> handover.accept(3L, EXPIRES_AT.minusHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
