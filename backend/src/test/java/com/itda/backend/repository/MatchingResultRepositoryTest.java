package com.itda.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;

@DataJpaTest
@ActiveProfiles("test")
class MatchingResultRepositoryTest {

    @org.springframework.beans.factory.annotation.Autowired
    private MatchingResultRepository matchingResultRepository;

    @Test
    void savesAndLoadsWithoutJpaRelations() {
        MatchingResult result = new MatchingResult(
                1L, 2L, new BigDecimal("0.9123"), MatchingStatus.AUTO, "본문 근거", "matching-v1");

        MatchingResult saved = matchingResultRepository.save(result);

        MatchingResult found = matchingResultRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getJournalEntryId()).isEqualTo(1L);
        assertThat(found.getMatchedChildId()).isEqualTo(2L);
        assertThat(found.getStatus()).isEqualTo(MatchingStatus.AUTO);
    }

    @Test
    void findByStatusNot_excludesAutoStatus() {
        matchingResultRepository.save(
                new MatchingResult(1L, 2L, new BigDecimal("0.9"), MatchingStatus.AUTO, null, "v1"));
        matchingResultRepository.save(
                new MatchingResult(3L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, "v1"));

        var queue = matchingResultRepository.findByStatusNot(MatchingStatus.AUTO);

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).getStatus()).isEqualTo(MatchingStatus.REVIEW);
    }
}
