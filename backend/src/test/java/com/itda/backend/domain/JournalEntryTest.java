package com.itda.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class JournalEntryTest {

    private JournalEntry pending() {
        return JournalEntry.of(1L, LocalDate.of(2026, 9, 1), "점심시간에 식사를 잘함", 1);
    }

    private JournalEntry matching() {
        JournalEntry entry = pending();
        entry.startMatching();
        return entry;
    }

    @Test
    void 대기_중인_일지만_매칭을_시작할_수_있다() {
        JournalEntry entry = pending();

        entry.startMatching();

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.MATCHING);
        assertThatThrownBy(entry::startMatching).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 매칭이_확정되면_아동이_채워지고_검증_대기_상태가_된다() {
        JournalEntry entry = matching();

        entry.confirmMatch(8L);

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
        assertThat(entry.getChildId()).isEqualTo(8L);
    }

    @Test
    void 매칭이_애매하면_사람_확인_상태가_되고_아동은_비어_있다() {
        JournalEntry entry = matching();

        entry.requestMatchReview();

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.MATCH_REVIEW);
        assertThat(entry.getChildId()).isNull();
    }

    @Test
    void 매칭_호출이_실패하면_실패_상태가_된다() {
        JournalEntry entry = matching();

        entry.failMatching();

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.FAILED);
    }

    @Test
    void 처리하다_멈춘_일지는_다시_대기_상태로_되돌린다() {
        JournalEntry entry = matching();

        entry.releaseMatching();

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.PENDING);
    }

    @Test
    void 매칭_중이_아니면_결과를_반영할_수_없다() {
        assertThatThrownBy(() -> pending().confirmMatch(8L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending().requestMatchReview()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending().failMatching()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending().releaseMatching()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 확정할_아동은_필수다() {
        assertThatThrownBy(() -> matching().confirmMatch(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private JournalEntry inReview() {
        JournalEntry entry = matching();
        entry.requestMatchReview();
        return entry;
    }

    private JournalEntry failed() {
        JournalEntry entry = matching();
        entry.failMatching();
        return entry;
    }

    @Test
    void 사람_확인_대기는_확인_필요와_실패_상태다() {
        assertThat(inReview().isAwaitingReview()).isTrue();
        assertThat(failed().isAwaitingReview()).isTrue();
        assertThat(pending().isAwaitingReview()).isFalse();
        assertThat(matching().isAwaitingReview()).isFalse();
    }

    @Test
    void 선생님이_아동을_고르면_AI_자동_확정과_같은_검증_대기_상태가_된다() {
        for (JournalEntry entry : List.of(inReview(), failed())) {
            entry.confirmMatchByReviewer(8L);

            assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
            assertThat(entry.getChildId()).isEqualTo(8L);
        }
    }

    @Test
    void 선생님이_제외하면_제외_상태가_되고_아동은_비어_있다() {
        for (JournalEntry entry : List.of(inReview(), failed())) {
            entry.exclude();

            assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.EXCLUDED);
            assertThat(entry.getChildId()).isNull();
        }
    }

    @Test
    void 사람_확인_대기가_아니면_선생님이_처리할_수_없다() {
        JournalEntry matched = inReview();
        matched.confirmMatchByReviewer(8L);

        for (JournalEntry entry : List.of(pending(), matching(), matched)) {
            assertThatThrownBy(() -> entry.confirmMatchByReviewer(9L)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(entry::exclude).isInstanceOf(IllegalStateException.class);
        }
    }

    private JournalEntry matched() {
        JournalEntry entry = matching();
        entry.confirmMatch(8L);
        return entry;
    }

    private JournalEntry validating() {
        JournalEntry entry = matched();
        entry.startValidating();
        return entry;
    }

    @Test
    void 매칭이_확정된_일지만_검증을_시작할_수_있다() {
        JournalEntry entry = matched();

        entry.startValidating();

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATING);
        assertThatThrownBy(entry::startValidating).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending().startValidating()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inReview().startValidating()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 검증을_통과하면_요약_대기_상태가_된다() {
        JournalEntry entry = validating();

        entry.passValidation();

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATED);
    }

    @Test
    void 검증에서_막히면_수정_요청_상태가_된다() {
        JournalEntry entry = validating();

        entry.blockValidation();

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_BLOCKED);
    }

    @Test
    void 검증_호출이_실패하면_실패_상태가_된다() {
        JournalEntry entry = validating();

        entry.failValidation();

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.FAILED);
    }

    @Test
    void 검증하다_멈춘_일지는_다시_검증_대기로_되돌리고_아동은_유지한다() {
        JournalEntry entry = validating();

        entry.releaseValidating();

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
        assertThat(entry.getChildId()).isEqualTo(8L);
    }

    @Test
    void 검증_중이_아니면_검증_결과를_반영할_수_없다() {
        assertThatThrownBy(() -> matched().passValidation()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> matched().blockValidation()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> matched().failValidation()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> matched().releaseValidating()).isInstanceOf(IllegalStateException.class);
    }

    private JournalEntry validated() {
        JournalEntry entry = validating();
        entry.passValidation();
        return entry;
    }

    private JournalEntry gate1Pending() {
        JournalEntry entry = validated();
        entry.startSummarizing();
        entry.completeSummary(50L);
        return entry;
    }

    @Test
    void 검증을_통과한_일지만_요약을_시작할_수_있다() {
        JournalEntry entry = validated();

        entry.startSummarizing();

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.SUMMARIZING);
        assertThatThrownBy(entry::startSummarizing).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> matched().startSummarizing()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 요약이_끝나면_어느_요약에_들어갔는지_남기고_Gate1_대기가_된다() {
        JournalEntry entry = validated();
        entry.startSummarizing();

        entry.completeSummary(50L);

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.GATE1_PENDING);
        assertThat(entry.getSummaryId()).isEqualTo(50L);
    }

    @Test
    void 승인_전_요약에_들어간_일지는_새_일지와_함께_다시_요약할_수_있다() {
        JournalEntry entry = gate1Pending();

        entry.startSummarizing();

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.SUMMARIZING);
        assertThat(entry.getSummaryId()).isEqualTo(50L);
    }

    @Test
    void 요약_호출이_실패하면_처음_요약하던_일지는_실패_상태가_된다() {
        JournalEntry entry = validated();
        entry.startSummarizing();

        entry.failSummary();

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.FAILED);
    }

    @Test
    void 다시_요약하다_실패해도_이미_요약에_들어간_일지는_Gate1_대기로_남는다() {
        JournalEntry entry = gate1Pending();
        entry.startSummarizing();

        entry.failSummary();

        // 기존 요약은 그대로 유효하다. 재요약 실패로 그 요약까지 무너뜨리지 않는다.
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.GATE1_PENDING);
        assertThat(entry.getSummaryId()).isEqualTo(50L);
    }

    @Test
    void 요약하다_멈춘_일지는_요약하기_전_상태로_되돌린다() {
        JournalEntry fresh = validated();
        fresh.startSummarizing();
        JournalEntry resummarizing = gate1Pending();
        resummarizing.startSummarizing();

        fresh.releaseSummarizing();
        resummarizing.releaseSummarizing();

        assertThat(fresh.getStatus()).isEqualTo(JournalEntryStatus.VALIDATED);
        assertThat(resummarizing.getStatus()).isEqualTo(JournalEntryStatus.GATE1_PENDING);
    }

    @Test
    void 요약_중이_아니면_요약_결과를_반영할_수_없다() {
        assertThatThrownBy(() -> validated().completeSummary(50L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validated().failSummary()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validated().releaseSummarizing()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 요약_ID는_필수다() {
        JournalEntry entry = validated();
        entry.startSummarizing();

        assertThatThrownBy(() -> entry.completeSummary(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
