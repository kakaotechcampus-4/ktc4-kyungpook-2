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
    void 문장_카드로_고쳐_승인하면_claims_만_바뀌고_AI_원문은_그대로다() {
        SummaryResult result = summary();
        String originalContent = result.getContent();
        String originalRaw = result.getRawResponse();

        result.approveWithClaims("[{\"text\":\"고친 문장\",\"source\":\"EDITED\"}]");

        assertThat(result.getStatus()).isEqualTo(SummaryStatus.APPROVED);
        assertThat(result.getClaims()).isEqualTo("[{\"text\":\"고친 문장\",\"source\":\"EDITED\"}]");
        assertThat(result.getContent()).isEqualTo(originalContent);
        assertThat(result.getRawResponse()).isEqualTo(originalRaw);
    }

    @Test
    void 문장_카드로_승인하려면_Gate1_대기여야_하고_claims_가_있어야_한다() {
        SummaryResult held = summary();
        held.hold();

        assertThatThrownBy(() -> held.approveWithClaims("[]")).isInstanceOf(IllegalStateException.class);
        assertThat(held.getStatus()).isEqualTo(SummaryStatus.HOLD);
        assertThatThrownBy(() -> summary().approveWithClaims(" ")).isInstanceOf(IllegalArgumentException.class);
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

    private SummaryResult noContentSummary() {
        return SummaryResult.noContent(8L, DATE, 3L, 1, "[1041,1042]", "{\"no_content\":true}");
    }

    @Test
    void 요약할_내용이_없으면_빈_본문과_빈_claims_로_Gate1_대기에_만든다() {
        SummaryResult result = noContentSummary();

        assertThat(result.isNoContent()).isTrue();
        assertThat(result.getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getClaims()).isEqualTo("[]");
        assertThat(result.getCoveredEntryIds()).isEqualTo("[]");
        assertThat(result.getUncoveredEntryIds()).isEqualTo("[1041,1042]");
        assertThat(result.getRawResponse()).isEqualTo("{\"no_content\":true}");
    }

    @Test
    void 일반_요약은_요약할_내용_없음이_아니다() {
        assertThat(summary().isNoContent()).isFalse();
    }

    @Test
    void 요약할_내용_없음도_묶음_키와_판_번호는_필수다() {
        assertThatThrownBy(() -> SummaryResult.noContent(null, DATE, 3L, 1, "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SummaryResult.noContent(8L, DATE, 3L, 0, "[]", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 승인_전_판은_요약할_내용_없음과_일반_요약으로_서로_덮어쓴다() {
        SummaryResult result = summary();
        result.markReview(true, "[\"다른아동이름\"]");

        // 같은 날 "특이사항 없음" 일지만 남기고 나머지를 지운 뒤 다시 요약한 경우
        result.overwriteNoContent("[1042]", "{\"no_content\":true}");

        assertThat(result.isNoContent()).isTrue();
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getClaims()).isEqualTo("[]");
        // 반영된 일지가 남아 있으면 Gate 1 원본 목록에서 빠진다 — 요약할 내용 없음은 언제나 비운다.
        assertThat(result.getCoveredEntryIds()).isEqualTo("[]");
        assertThat(result.getUncoveredEntryIds()).isEqualTo("[1042]");
        assertThat(result.isNeedsReview()).isFalse();

        // 그 뒤 내용 있는 일지가 와서 다시 요약하면 일반 요약으로 돌아온다
        result.overwrite("블록 놀이에서 양보했다.", "[{\"text\":\"블록 놀이에서 양보했다.\"}]", "[1043]", "[1042]", "{}");

        assertThat(result.isNoContent()).isFalse();
        assertThat(result.getContent()).isEqualTo("블록 놀이에서 양보했다.");
    }

    @Test
    void 승인_뒤에는_요약할_내용_없음으로_덮어쓸_수_없다() {
        SummaryResult approved = summary();
        approved.approve();

        assertThatThrownBy(() -> approved.overwriteNoContent("[]", "{}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 요약할_내용_없음을_확인하면_확인_상태가_되고_더는_덮어쓸_수_없다() {
        SummaryResult result = noContentSummary();

        result.confirmNoContent();

        assertThat(result.getStatus()).isEqualTo(SummaryStatus.CONFIRMED);
        assertThat(result.isBeforeApproval()).isFalse();
        assertThatThrownBy(result::confirmNoContent).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 요약할_내용_없음을_되돌리면_되돌림_상태가_되고_더는_덮어쓸_수_없다() {
        // 다음 [요약] 결과는 이 판을 덮어쓰지 않고 새 판으로 쌓인다 — 되돌렸다는 기록이 남는다.
        SummaryResult result = noContentSummary();

        result.revertNoContent();

        assertThat(result.getStatus()).isEqualTo(SummaryStatus.REVERTED);
        assertThat(result.isBeforeApproval()).isFalse();
        assertThatThrownBy(result::revertNoContent).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(result::confirmNoContent).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 일반_요약은_되돌릴_수_없다() {
        SummaryResult result = summary();

        assertThatThrownBy(result::revertNoContent).isInstanceOf(IllegalStateException.class);
        assertThat(result.getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
    }

    @Test
    void 일반_요약은_확인으로_닫을_수_없다() {
        SummaryResult result = summary();

        assertThatThrownBy(result::confirmNoContent).isInstanceOf(IllegalStateException.class);
        assertThat(result.getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
    }

    @Test
    void 요약할_내용_없음은_승인_보류_제거할_수_없다() {
        // 승인하면 빈 글이 child_context 로 올라가 인사이트 재료가 된다.
        SummaryResult result = noContentSummary();

        assertThatThrownBy(result::approve).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> result.approveWithClaims("[{\"text\":\"새 문장\"}]"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(result::hold).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(result::remove).isInstanceOf(IllegalStateException.class);
        assertThat(result.getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
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
