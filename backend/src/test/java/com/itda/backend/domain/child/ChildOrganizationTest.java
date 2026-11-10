package com.itda.backend.domain.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChildOrganizationTest {

    @Test
    void keepsExternalId() {
        ChildOrganization link = ChildOrganization.of(1L, 10L, "2026-0031");

        assertThat(link.getExternalId()).isEqualTo("2026-0031");
    }

    @Test
    void externalIdIsOptional() {
        assertThat(ChildOrganization.of(1L, 10L, null).getExternalId()).isNull();
        assertThat(ChildOrganization.of(1L, 10L).getExternalId()).isNull();
    }

    @Test
    void rejectsBlankOrTooLongExternalId() {
        assertThatThrownBy(() -> ChildOrganization.of(1L, 10L, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChildOrganization.of(1L, 10L, "가".repeat(51)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ChildOrganization.of(1L, 10L, "가".repeat(50)).getExternalId()).hasSize(50);
    }

    @Test
    void deleteClearsExternalIdSoTheNumberCanBeReused() {
        ChildOrganization link = ChildOrganization.of(1L, 10L, "2026-0031");

        link.delete();

        assertThat(link.isDeleted()).isTrue();
        assertThat(link.getExternalId()).isNull();
    }

    @Test
    void restoreDoesNotBringBackTheOldExternalId() {
        ChildOrganization link = ChildOrganization.of(1L, 10L, "2026-0031");
        link.delete();

        link.restore();

        assertThat(link.isDeleted()).isFalse();
        assertThat(link.getExternalId()).isNull();
    }

    @Test
    void restoreWithExternalIdSetsTheNewNumber() {
        ChildOrganization link = ChildOrganization.of(1L, 10L, "2026-0031");
        link.delete();

        assertThatThrownBy(() -> link.restore(" ")).isInstanceOf(IllegalArgumentException.class);
        link.restore("C-9");

        assertThat(link.isDeleted()).isFalse();
        assertThat(link.getExternalId()).isEqualTo("C-9");
    }
}
