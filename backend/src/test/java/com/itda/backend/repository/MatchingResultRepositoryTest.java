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
                1L, 2L, new BigDecimal("0.9123"), MatchingStatus.AUTO,
                "[{\"child_id\":2,\"confidence\":0.91}]", "[{\"start\":0,\"end\":3}]",
                "본문 근거", "matching-v1");

        MatchingResult saved = matchingResultRepository.save(result);

        MatchingResult found = matchingResultRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getJournalEntryId()).isEqualTo(1L);
        assertThat(found.getMatchedChildId()).isEqualTo(2L);
        assertThat(found.getStatus()).isEqualTo(MatchingStatus.AUTO);
        // candidates/evidence/rawResponse 인자 순서가 뒤바뀌어도 컴파일은 통과하므로
        // 값 자체를 명시적으로 확인한다 (리뷰에서 지적된 인자 전치 위험 방지).
        assertThat(found.getCandidates()).isEqualTo("[{\"child_id\":2,\"confidence\":0.91}]");
        assertThat(found.getEvidence()).isEqualTo("[{\"start\":0,\"end\":3}]");
        assertThat(found.getRawResponse()).isEqualTo("본문 근거");
    }

    @Test
    void resolveAsNotOurs_removesItemFromQueue() {
        // 버그 재발 방지: resolveAsNotOurs()가 UNMATCHED를 쓰면 findByStatusNot(AUTO)에
        // 계속 걸려서 큐에서 안 빠졌다. 실제 리포지토리 쿼리로 끝까지 확인한다.
        MatchingResult saved = matchingResultRepository.save(
                new MatchingResult(1L, null, new BigDecimal("0.2"), MatchingStatus.UNMATCHED,
                        null, null, null, "v1"));

        saved.resolveAsNotOurs("kakao-teacher-1");
        matchingResultRepository.save(saved);

        var queue = matchingResultRepository.findByStatusNot(MatchingStatus.AUTO);
        assertThat(queue).isEmpty();
    }

    @Test
    void findByStatusNot_excludesAutoStatus() {
        matchingResultRepository.save(
                new MatchingResult(1L, 2L, new BigDecimal("0.9"), MatchingStatus.AUTO,
                        null, null, null, "v1"));
        matchingResultRepository.save(
                new MatchingResult(3L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW,
                        null, null, null, "v1"));

        var queue = matchingResultRepository.findByStatusNot(MatchingStatus.AUTO);

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).getStatus()).isEqualTo(MatchingStatus.REVIEW);
    }
}
