package com.itda.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.ValidationResult;
import com.itda.backend.domain.ValidationVerdict;

@DataJpaTest
@ActiveProfiles("test")
class ValidationResultRepositoryTest {

    @Autowired
    private ValidationResultRepository validationResultRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void 연관관계_없이_저장하고_생성_시각을_남긴다() {
        ValidationResult saved = validationResultRepository.saveAndFlush(ValidationResult.of(1041L, 7L, 8L,
                ValidationVerdict.REVIEW, "[\"추측성표현\"]", "[{\"start\":0,\"end\":5}]", "{\"verdict\":\"REVIEW\"}"));
        entityManager.clear();

        ValidationResult found = validationResultRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getJournalEntryId()).isEqualTo(1041L);
        assertThat(found.getMatchingResultId()).isEqualTo(7L);
        assertThat(found.getChildId()).isEqualTo(8L);
        assertThat(found.getVerdict()).isEqualTo(ValidationVerdict.REVIEW);
        // 문자열 인자 순서가 뒤바뀌어도 컴파일은 통과하므로 값 자체를 확인한다.
        assertThat(found.getIssueTypes()).isEqualTo("[\"추측성표현\"]");
        assertThat(found.getEvidence()).isEqualTo("[{\"start\":0,\"end\":5}]");
        assertThat(found.getRawResponse()).isEqualTo("{\"verdict\":\"REVIEW\"}");
        assertThat(found.getCreatedAt()).isNotNull();
    }
}
