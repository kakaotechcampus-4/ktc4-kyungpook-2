package com.itda.backend.domain.insight;

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

    @Test
    void 인사이트가_0건이어도_끝난_것이다() {
        InsightRun run = InsightRun.claim(8L, FROM, TO, 1);

        run.finish(0, 41L);

        assertThat(run.getStatus()).isEqualTo(InsightRunStatus.DONE);
        assertThat(run.getInsightCount()).isZero();
    }

    @Test
    void 실패는_건수를_채우지_않는다() {
        InsightRun run = InsightRun.claim(8L, FROM, TO, 1);

        run.fail("llm_unavailable:timeout");

        assertThat(run.getStatus()).isEqualTo(InsightRunStatus.FAILED);
        assertThat(run.getInsightCount()).isNull();
    }

    @Test
    void 실패_사유가_길면_잘라_남긴다() {
        InsightRun run = InsightRun.claim(8L, FROM, TO, 1);

        run.fail("llm_unavailable:" + "x".repeat(100));

        assertThat(run.getFailureReason()).hasSize(60).startsWith("llm_unavailable:x");
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
