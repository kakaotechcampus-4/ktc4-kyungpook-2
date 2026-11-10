package com.itda.backend.domain.summary;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChildContextTest {

    @Test
    void 아동_ID가_없으면_만들지_못한다() {
        assertThatThrownBy(() -> ChildContext.of(null, 31L, "승인된 요약"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 출처_요약_ID가_없으면_만들지_못한다() {
        assertThatThrownBy(() -> ChildContext.of(8L, null, "승인된 요약"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n"})
    void 본문이_비면_만들지_못한다(String content) {
        assertThatThrownBy(() -> ChildContext.of(8L, 31L, content))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 본문이_null이면_만들지_못한다() {
        assertThatThrownBy(() -> ChildContext.of(8L, 31L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
