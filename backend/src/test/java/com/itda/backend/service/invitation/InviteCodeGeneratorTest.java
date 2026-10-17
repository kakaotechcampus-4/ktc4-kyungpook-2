package com.itda.backend.service.invitation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class InviteCodeGeneratorTest {

    private final InviteCodeGenerator generator = new InviteCodeGenerator();

    @RepeatedTest(20)
    void generatesEightCharactersFromUnambiguousAlphabet() {
        String code = generator.generate();

        assertThat(code).hasSize(8).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}");
    }

    @Test
    void alphabetExcludesLookalikes() {
        assertThat(InviteCodeGenerator.ALPHABET).hasSize(31).doesNotContain("0", "O", "1", "I", "L");
    }

    @Test
    void normalizeIgnoresHyphensSpacesAndCase() {
        assertThat(InviteCodeGenerator.normalize("k7p3-qx9m")).isEqualTo("K7P3QX9M");
        assertThat(InviteCodeGenerator.normalize(" K7P3 QX9M ")).isEqualTo("K7P3QX9M");
    }

    @Test
    void normalizeRejectsWrongLengthOrCharacters() {
        assertThat(InviteCodeGenerator.normalize(null)).isNull();
        assertThat(InviteCodeGenerator.normalize("K7P3QX")).isNull();
        assertThat(InviteCodeGenerator.normalize("K7P3QX9MZ")).isNull();
        assertThat(InviteCodeGenerator.normalize("K7P3QX0M")).isNull();
        assertThat(InviteCodeGenerator.normalize("K7P3QX!M")).isNull();
    }
}
