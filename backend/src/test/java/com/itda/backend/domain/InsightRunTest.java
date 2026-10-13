package com.itda.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class InsightRunTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 7);

    @Test
    void 집으면_RUNNING으로_시작한다() {
        InsightRun run = InsightRun.claim(8L, FROM, TO, 1);

        assertThat(run.getStatus()).isEqualTo(InsightRunStatus.RUNNING);
        assertThat(run.getInsightCount()).isNull();
    }

    /** 이 테이블을 둔 이유가 이것이다 — 0건도 "처리했다" 로 남아야 다시 부르지 않는다. */
    @Test
    void 인사이트가_0건이어도_끝난_것이다() {
        InsightRun run = InsightRun.claim(8L, FROM, TO, 1);

        run.finish(0);

        assertThat(run.getStatus()).isEqualTo(InsightRunStatus.DONE);
        assertThat(run.getInsightCount()).isZero();
    }

    /** 0건으로 끝난 것과 못 끝낸 것이 구별돼야 한다. */
    @Test
    void 실패는_건수를_채우지_않는다() {
        InsightRun run = InsightRun.claim(8L, FROM, TO, 1);

        run.fail();

        assertThat(run.getStatus()).isEqualTo(InsightRunStatus.FAILED);
        assertThat(run.getInsightCount()).isNull();
    }

    @Test
    void 기간의_끝이_시작보다_빠르면_만들지_못한다() {
        assertThatThrownBy(() -> InsightRun.claim(8L, TO, FROM, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 판_번호는_1부터다() {
        assertThatThrownBy(() -> InsightRun.claim(8L, FROM, TO, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 아동_ID가_없으면_만들지_못한다() {
        assertThatThrownBy(() -> InsightRun.claim(null, FROM, TO, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
