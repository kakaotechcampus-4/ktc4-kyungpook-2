package com.itda.backend.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChildContextTest {

    /** 누구의 기록인지 모르면 타임라인에 올릴 수 없다. */
    @Test
    void 아동_ID가_없으면_만들지_못한다() {
        assertThatThrownBy(() -> ChildContext.of(null, 31L, "승인된 요약"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 출처를 모르면 날짜·기관·판수를 찾을 수 없다 — 화면이 쓰는 값이 전부 거기 있다. */
    @Test
    void 출처_요약_ID가_없으면_만들지_못한다() {
        assertThatThrownBy(() -> ChildContext.of(8L, null, "승인된 요약"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 빈 글이 승인본으로 올라가면 다른 기관이 빈 타임라인을 읽게 된다. */
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
