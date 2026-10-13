package com.itda.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.SummaryResult;
import com.itda.backend.domain.SummaryStatus;

@DataJpaTest
@ActiveProfiles("test")
class SummaryResultRepositoryTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);

    @Autowired
    private SummaryResultRepository summaryResultRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void 연관관계_없이_저장하고_생성_수정_시각을_남긴다() {
        SummaryResult saved = summaryResultRepository.saveAndFlush(SummaryResult.of(8L, DATE, 3L, 1, "요약 본문",
                "[{\"text\":\"요약 본문\"}]", "[1041]", "[1042]", "{\"content\":\"요약 본문\"}"));
        entityManager.clear();

        SummaryResult found = summaryResultRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getChildId()).isEqualTo(8L);
        assertThat(found.getEntryDate()).isEqualTo(DATE);
        assertThat(found.getInstitutionId()).isEqualTo(3L);
        assertThat(found.getRevision()).isEqualTo(1);
        assertThat(found.getStatus()).isEqualTo(SummaryStatus.GATE1_PENDING);
        // 문자열 인자 순서가 뒤바뀌어도 컴파일은 통과하므로 값 자체를 확인한다.
        assertThat(found.getContent()).isEqualTo("요약 본문");
        assertThat(found.getClaims()).isEqualTo("[{\"text\":\"요약 본문\"}]");
        assertThat(found.getCoveredEntryIds()).isEqualTo("[1041]");
        assertThat(found.getUncoveredEntryIds()).isEqualTo("[1042]");
        assertThat(found.getRawResponse()).isEqualTo("{\"content\":\"요약 본문\"}");
        assertThat(found.isNeedsReview()).isFalse();
        assertThat(found.getReviewReasons()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void 같은_묶음의_같은_판은_두_번_저장할_수_없다() {
        summaryResultRepository.saveAndFlush(SummaryResult.of(8L, DATE, 3L, 1, "첫 요약", "[]", "[]", "[]", "{}"));

        assertThatThrownBy(() -> summaryResultRepository.saveAndFlush(
                SummaryResult.of(8L, DATE, 3L, 1, "중복 요약", "[]", "[]", "[]", "{}")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 기관이_다르면_같은_아이_같은_날짜라도_따로_저장한다() {
        summaryResultRepository.saveAndFlush(SummaryResult.of(8L, DATE, 3L, 1, "학교 요약", "[]", "[]", "[]", "{}"));
        summaryResultRepository.saveAndFlush(SummaryResult.of(8L, DATE, 4L, 1, "센터 요약", "[]", "[]", "[]", "{}"));

        assertThat(summaryResultRepository.count()).isEqualTo(2);
    }
}
