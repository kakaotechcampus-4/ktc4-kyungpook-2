package com.itda.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MatchingResultTest {

    @Test
    void 호출_실패_결과는_판정_내용_없이_FAILED로_남는다() {
        MatchingResult result = MatchingResult.failed(10L);

        assertThat(result.getJournalEntryId()).isEqualTo(10L);
        assertThat(result.getStatus()).isEqualTo(MatchingStatus.FAILED);
        assertThat(result.getMatchedChildId()).isNull();
        assertThat(result.getConfidence()).isNull();
        assertThat(result.getCandidates()).isNull();
        assertThat(result.getRawResponse()).isNull();
        assertThat(result.getCreatedAt()).isNotNull();
    }
}
