package com.itda.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ValidationResultTest {

    @Test
    void 검증_결과를_만든다() {
        ValidationResult result = ValidationResult.of(1041L, 7L, 8L, ValidationVerdict.BLOCK,
                "[\"개인정보표현\"]", "[{\"start\":23,\"end\":36}]", "{\"verdict\":\"BLOCK\"}");

        assertThat(result.getJournalEntryId()).isEqualTo(1041L);
        assertThat(result.getMatchingResultId()).isEqualTo(7L);
        assertThat(result.getChildId()).isEqualTo(8L);
        assertThat(result.getVerdict()).isEqualTo(ValidationVerdict.BLOCK);
        assertThat(result.getIssueTypes()).isEqualTo("[\"개인정보표현\"]");
        assertThat(result.getEvidence()).isEqualTo("[{\"start\":23,\"end\":36}]");
        assertThat(result.getRawResponse()).isEqualTo("{\"verdict\":\"BLOCK\"}");
    }

    @Test
    void 매칭_결과와_아동은_비어_있어도_된다() {
        ValidationResult result = ValidationResult.of(1041L, null, null, ValidationVerdict.PASS, "[]", "[]", "{}");

        assertThat(result.getMatchingResultId()).isNull();
        assertThat(result.getChildId()).isNull();
    }

    @Test
    void 일지와_판정은_필수다() {
        assertThatThrownBy(() -> ValidationResult.of(null, 7L, 8L, ValidationVerdict.PASS, "[]", "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ValidationResult.of(1041L, 7L, 8L, null, "[]", "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
