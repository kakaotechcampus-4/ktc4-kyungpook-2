package com.itda.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.ChildContext;

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

    /** 타임라인(O-14)은 최근 승인부터 본다. */
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

    /**
     * 한 요약에 승인본은 하나다 (코드리뷰 반영).
     *
     * <p>승인된 요약을 다시 승인하는 경로가 없다(§8.2) — 반려 상태가 없고, 승인 뒤 새 일지가 오면
     * 그 요약을 고치지 않고 revision + 1 로 새 요약을 만든다. 제약이 없으면 Gate 1 승인 API 를
     * 두 번 호출했을 때 같은 글이 조용히 두 번 쌓인다.
     */
    @Test
    void 같은_요약으로_두_번_승인하면_막힌다() {
        childContextRepository.saveAndFlush(ChildContext.of(8L, 31L, "처음 승인한 글"));

        assertThatThrownBy(() ->
                childContextRepository.saveAndFlush(ChildContext.of(8L, 31L, "실수로 한 번 더 승인")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 판이 바뀌면(revision + 1) 다른 요약이므로 같은 아이에게 승인본이 여러 건 쌓인다. */
    @Test
    void 다른_요약이면_같은_아이에게_여러_건_쌓인다() {
        childContextRepository.saveAndFlush(ChildContext.of(8L, 31L, "1판 승인본"));
        childContextRepository.saveAndFlush(ChildContext.of(8L, 32L, "2판 승인본"));
        entityManager.clear();

        assertThat(childContextRepository.findByChildIdOrderByIdDesc(8L)).hasSize(2);
    }
}
