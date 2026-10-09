package com.itda.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class SummaryResultTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);

    private SummaryResult summary() {
        return SummaryResult.of(8L, DATE, 3L, "블록 놀이에서 친구에게 양보했다.",
                "[{\"text\":\"블록 놀이에서 친구에게 양보했다.\"}]", "[1041]", "[1042]", "{\"content\":\"...\"}");
    }

    @Test
    void 첫_요약은_1번째_판으로_Gate1_대기_상태로_만든다() {
        SummaryResult result = summary();

        assertThat(result.getChildId()).isEqualTo(8L);
        assertThat(result.getEntryDate()).isEqualTo(DATE);
        assertThat(result.getInstitutionId()).isEqualTo(3L);
        assertThat(result.getRevision()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
        assertThat(result.getContent()).isEqualTo("블록 놀이에서 친구에게 양보했다.");
        assertThat(result.getClaims()).isEqualTo("[{\"text\":\"블록 놀이에서 친구에게 양보했다.\"}]");
        assertThat(result.getCoveredEntryIds()).isEqualTo("[1041]");
        assertThat(result.getUncoveredEntryIds()).isEqualTo("[1042]");
        assertThat(result.getRawResponse()).isEqualTo("{\"content\":\"...\"}");
    }

    @Test
    void 묶음_키와_본문은_필수다() {
        assertThatThrownBy(() -> SummaryResult.of(null, DATE, 3L, "본문", "[]", "[]", "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SummaryResult.of(8L, null, 3L, "본문", "[]", "[]", "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SummaryResult.of(8L, DATE, null, "본문", "[]", "[]", "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SummaryResult.of(8L, DATE, 3L, " ", "[]", "[]", "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 승인_전에는_새_일지까지_묶은_결과로_같은_판을_덮어쓴다() {
        SummaryResult result = summary();

        result.overwrite("블록 놀이에서 양보했고 점심을 남김없이 먹었다.", "[]", "[1041,1043]", "[]", "{\"v\":2}");

        assertThat(result.getRevision()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
        assertThat(result.getContent()).isEqualTo("블록 놀이에서 양보했고 점심을 남김없이 먹었다.");
        assertThat(result.getCoveredEntryIds()).isEqualTo("[1041,1043]");
        assertThat(result.getUncoveredEntryIds()).isEqualTo("[]");
        assertThat(result.getRawResponse()).isEqualTo("{\"v\":2}");
    }

    @Test
    void 빈_본문으로는_덮어쓸_수_없다() {
        assertThatThrownBy(() -> summary().overwrite("", "[]", "[]", "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
