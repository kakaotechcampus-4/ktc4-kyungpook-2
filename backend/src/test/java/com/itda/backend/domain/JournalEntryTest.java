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
}
