package com.itda.backend.domain.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class SummaryResultTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);

    private SummaryResult summary() {
        return SummaryResult.of(8L, DATE, 3L, 1, "블록 놀이에서 친구에게 양보했다.",
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
        assertThatThrownBy(() -> SummaryResult.of(null, DATE, 3L, 1, "본문", "[]", "[]", "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SummaryResult.of(8L, null, 3L, 1, "본문", "[]", "[]", "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SummaryResult.of(8L, DATE, null, 1, "본문", "[]", "[]", "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SummaryResult.of(8L, DATE, 3L, 1, " ", "[]", "[]", "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SummaryResult.of(8L, DATE, 3L, 0, "본문", "[]", "[]", "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 승인된_판이_있으면_다음_판으로_만든다() {
        assertThat(SummaryResult.of(8L, DATE, 3L, 2, "새 일지만 묶은 요약", "[]", "[]", "[]", "{}").getRevision())
                .isEqualTo(2);
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
    void 처음에는_사람이_볼_필요가_없는_것으로_만든다() {
        SummaryResult result = summary();

        assertThat(result.isNeedsReview()).isFalse();
        assertThat(result.getReviewReasons()).isNull();
    }

    @Test
    void 다른_아이_이름이_남았다는_표시를_남기고_다시_요약하면_새_결과로_바꾼다() {
        SummaryResult result = summary();

        result.markReview(true, "[\"다른아동이름\"]");
        assertThat(result.isNeedsReview()).isTrue();
        assertThat(result.getReviewReasons()).isEqualTo("[\"다른아동이름\"]");

        // 다시 요약해 이름이 사라졌으면 표시도 사라져야 한다 — 남아 있으면 교사가 없는 문제를 찾는다.
        result.markReview(false, "[]");
        assertThat(result.isNeedsReview()).isFalse();
        assertThat(result.getReviewReasons()).isEqualTo("[]");
    }

    @Test
    void 덮어쓰면_이전_요약의_표시를_지운다() {
        SummaryResult result = summary();
        result.markReview(true, "[\"다른아동이름\"]");

        // 새 본문에 대한 표시는 새 응답이 정한다. 이전 본문의 경고가 새 글에 붙어 있으면 안 된다.
        result.overwrite("다시 만든 요약", "[]", "[]", "[]", "{}");

        assertThat(result.isNeedsReview()).isFalse();
        assertThat(result.getReviewReasons()).isNull();
    }

    @Test
    void 빈_본문으로는_덮어쓸_수_없다() {
        assertThatThrownBy(() -> summary().overwrite("", "[]", "[]", "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 승인하면_승인_상태가_되고_더는_덮어쓸_수_없다() {
        SummaryResult result = summary();

        result.approve();

        assertThat(result.getStatus()).isEqualTo(SummaryStatus.APPROVED);
        assertThat(result.isBeforeApproval()).isFalse();
        assertThatThrownBy(() -> result.overwrite("다시", "[]", "[]", "[]", "{}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 보류하면_보류_상태가_되고_보류_해제하면_Gate1_대기로_돌아온다() {
        SummaryResult result = summary();

        result.hold();
        assertThat(result.getStatus()).isEqualTo(SummaryStatus.HOLD);
        // 보류된 판은 승인 전이 아니다 — 같은 묶음에 새 일지가 와도 이 판을 덮어쓰지 않고 새 판을 만든다.
        assertThat(result.isBeforeApproval()).isFalse();

        result.reopen();
        assertThat(result.getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
    }

    @Test
    void 제거하면_제거_상태가_된다() {
        SummaryResult result = summary();

        result.remove();

        assertThat(result.getStatus()).isEqualTo(SummaryStatus.REMOVED);
        assertThat(result.isBeforeApproval()).isFalse();
    }

    @Test
    void Gate1_대기가_아니면_승인_보류_제거할_수_없다() {
        SummaryResult approved = summary();
        approved.approve();

        assertThatThrownBy(approved::approve).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(approved::hold).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(approved::remove).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 보류된_요약은_보류함에서_바로_제거할_수_있지만_승인은_보류를_해제한_뒤에_한다() {
        SummaryResult held = summary();
        held.hold();

        assertThatThrownBy(held::approve).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(held::hold).isInstanceOf(IllegalStateException.class);

        held.remove();
        assertThat(held.getStatus()).isEqualTo(SummaryStatus.REMOVED);
    }

    @Test
    void 보류된_요약만_보류_해제할_수_있다() {
        SummaryResult pending = summary();
        SummaryResult approved = summary();
        approved.approve();

        assertThatThrownBy(pending::reopen).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(approved::reopen).isInstanceOf(IllegalStateException.class);
    }
}
