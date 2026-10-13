package com.itda.backend.domain.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class SharingConsentTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 10, 0);

    @Test
    void 새_동의는_살아_있다() {
        SharingConsent consent = SharingConsent.grant(1L, 2L, 3L, List.of(ConsentField.DAILY_SUMMARY));

        assertThat(consent.isActive()).isTrue();
        assertThat(consent.getStatus()).isEqualTo(SharingConsentStatus.ACTIVE);
        assertThat(consent.getRevokedBy()).isNull();
        assertThat(consent.getRevokeReason()).isNull();
    }

    @Test
    void 항목은_중복을_합치고_정해진_순서로_둔다() {
        SharingConsent consent = SharingConsent.grant(1L, 2L, 3L,
                List.of(ConsentField.WEEKLY_INSIGHT, ConsentField.DAILY_SUMMARY, ConsentField.WEEKLY_INSIGHT));

        assertThat(consent.getScope()).containsExactly(ConsentField.DAILY_SUMMARY, ConsentField.WEEKLY_INSIGHT);
    }

    @Test
    void 빈_항목이나_비어_있는_값이_섞인_항목은_받지_않는다() {
        assertThatThrownBy(() -> SharingConsent.grant(1L, 2L, 3L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SharingConsent.grant(1L, 2L, 3L, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SharingConsent.grant(1L, 2L, 3L, Arrays.asList(ConsentField.DAILY_SUMMARY, null)))
                .isInstanceOf(IllegalArgumentException.class);

        SharingConsent consent = SharingConsent.grant(1L, 2L, 3L, List.of(ConsentField.DAILY_SUMMARY));
        assertThatThrownBy(() -> consent.changeScope(Arrays.asList(null, ConsentField.WEEKLY_INSIGHT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(consent.getScope()).containsExactly(ConsentField.DAILY_SUMMARY);
    }

    @Test
    void 아이_보호자_기관은_필수다() {
        assertThatThrownBy(() -> SharingConsent.grant(null, 2L, 3L, List.of(ConsentField.DAILY_SUMMARY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SharingConsent.grant(1L, null, 3L, List.of(ConsentField.DAILY_SUMMARY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SharingConsent.grant(1L, 2L, null, List.of(ConsentField.DAILY_SUMMARY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 철회하면_누가_언제_왜_껐는지_남는다() {
        SharingConsent consent = SharingConsent.grant(1L, 2L, 3L, List.of(ConsentField.DAILY_SUMMARY));

        consent.revoke(9L, ConsentRevokeReason.GUARDIAN_REVOKED, NOW);

        assertThat(consent.isActive()).isFalse();
        assertThat(consent.getStatus()).isEqualTo(SharingConsentStatus.REVOKED);
        assertThat(consent.getRevokedBy()).isEqualTo(9L);
        assertThat(consent.getRevokedAt()).isEqualTo(NOW);
        assertThat(consent.getRevokeReason()).isEqualTo(ConsentRevokeReason.GUARDIAN_REVOKED);
    }

    @Test
    void 사람이_아닌_경로의_철회는_철회자가_비어_있다() {
        SharingConsent consent = SharingConsent.grant(1L, 2L, 3L, List.of(ConsentField.DAILY_SUMMARY));

        consent.revoke(null, ConsentRevokeReason.ROSTER_REMOVED, NOW);

        assertThat(consent.getRevokedBy()).isNull();
        assertThat(consent.getRevokeReason()).isEqualTo(ConsentRevokeReason.ROSTER_REMOVED);
    }

    /** 철회된 행은 이력이다. 고치거나 다시 철회하면 이력이 바뀐다. */
    @Test
    void 철회된_동의는_바꿀_수_없다() {
        SharingConsent consent = SharingConsent.grant(1L, 2L, 3L, List.of(ConsentField.DAILY_SUMMARY));
        consent.revoke(9L, ConsentRevokeReason.GUARDIAN_REVOKED, NOW);

        assertThatThrownBy(() -> consent.changeScope(List.of(ConsentField.WEEKLY_INSIGHT)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> consent.revoke(8L, ConsentRevokeReason.GUARDIAN_UNLINKED, NOW.plusHours(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(consent.getRevokedBy()).isEqualTo(9L);
        assertThat(consent.getScope()).containsExactly(ConsentField.DAILY_SUMMARY);
    }

    @Test
    void 철회_사유와_시각은_필수다() {
        SharingConsent consent = SharingConsent.grant(1L, 2L, 3L, List.of(ConsentField.DAILY_SUMMARY));

        assertThatThrownBy(() -> consent.revoke(9L, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consent.revoke(9L, ConsentRevokeReason.GUARDIAN_REVOKED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(consent.isActive()).isTrue();
    }
}
