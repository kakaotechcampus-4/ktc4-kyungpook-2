package com.itda.backend.repository.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;

@DataJpaTest
@ActiveProfiles("test")
class ChildContextRepositoryTest {

    @Autowired
    private ChildContextRepository childContextRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void 연관관계_없이_저장하고_생성_시각을_남긴다() {
        ChildContext saved = childContextRepository.saveAndFlush(
                ChildContext.of(8L, 31L, "급식을 혼자 먹었고, 손을 잡고 세어 주자 진정되었다."));
        entityManager.clear();

        ChildContext found = childContextRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getChildId()).isEqualTo(8L);
        assertThat(found.getSummaryResultId()).isEqualTo(31L);
        assertThat(found.getContent()).isEqualTo("급식을 혼자 먹었고, 손을 잡고 세어 주자 진정되었다.");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 아동별로_최근_승인부터_가져온다() {
        childContextRepository.saveAndFlush(ChildContext.of(8L, 31L, "먼저 승인된 요약"));
        childContextRepository.saveAndFlush(ChildContext.of(8L, 32L, "나중에 승인된 요약"));
        childContextRepository.saveAndFlush(ChildContext.of(9L, 33L, "다른 아이의 요약"));
        entityManager.clear();

        assertThat(childContextRepository.findByChildIdOrderByIdDesc(8L))
                .extracting(ChildContext::getContent)
                .containsExactly("나중에 승인된 요약", "먼저 승인된 요약");
    }

    @Test
    void 같은_요약으로_두_번_승인하면_막힌다() {
        childContextRepository.saveAndFlush(ChildContext.of(8L, 31L, "처음 승인한 글"));

        assertThatThrownBy(() ->
                childContextRepository.saveAndFlush(ChildContext.of(8L, 31L, "실수로 한 번 더 승인")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 다른_요약이면_같은_아이에게_여러_건_쌓인다() {
        childContextRepository.saveAndFlush(ChildContext.of(8L, 31L, "1판 승인본"));
        childContextRepository.saveAndFlush(ChildContext.of(8L, 32L, "2판 승인본"));
        entityManager.clear();

        assertThat(childContextRepository.findByChildIdOrderByIdDesc(8L)).hasSize(2);
    }

    @Test
    void 그때_이후_승인된_요약의_아이와_관찰_날짜를_가져온다() {
        LocalDate oldEntry = LocalDate.of(2026, 8, 19);
        Long recentId = summary(8L, oldEntry);
        Long staleId = summary(9L, LocalDate.of(2026, 8, 20));
        childContextRepository.saveAndFlush(ChildContext.of(8L, recentId, "8월 기록을 이제 승인"));
        ChildContext stale = childContextRepository.saveAndFlush(ChildContext.of(9L, staleId, "오래전 승인"));
        entityManager.getEntityManager()
                .createNativeQuery("update child_context set created_at = :at where id = :id")
                .setParameter("at", LocalDateTime.now().minusDays(30))
                .setParameter("id", stale.getId())
                .executeUpdate();
        entityManager.clear();

        assertThat(childContextRepository.findApprovedEntryDaysSince(LocalDateTime.now().minusDays(14)))
                .containsExactly(new ApprovedEntryDay(8L, oldEntry));
    }

    private Long summary(Long childId, LocalDate entryDate) {
        return entityManager.persistAndFlush(SummaryResult.of(childId, entryDate, 3L, 1, "요약 본문",
                "[{\"text\":\"요약 본문\"}]", "[1041]", "[]", "{}")).getId();
    }
}
