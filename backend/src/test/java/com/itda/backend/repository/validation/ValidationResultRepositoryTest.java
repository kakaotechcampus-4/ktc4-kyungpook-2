package com.itda.backend.repository.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;

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

    /**
     * 수정 요청 큐의 처리(#357)는 화면이 보던 판정이 그 일지의 최신 판정인지 본다. 다시 검증하면 판정 행이 쌓이므로
     * 그 일지의 가장 큰 id 를 고른다. 다른 일지의 판정은 섞이지 않는다.
     */
    @Test
    void 그_일지의_가장_최근_판정을_고른다() {
        validationResultRepository.saveAndFlush(
                ValidationResult.of(1L, null, 8L, ValidationVerdict.BLOCK, "[\"진단명\"]", null, null));
        ValidationResult latest = validationResultRepository.saveAndFlush(
                ValidationResult.of(1L, null, 8L, ValidationVerdict.BLOCK, "[\"개인정보표현\"]", null, null));
        validationResultRepository.saveAndFlush(
                ValidationResult.of(2L, null, 8L, ValidationVerdict.PASS, null, null, null));
        entityManager.clear();

        assertThat(validationResultRepository.findFirstByJournalEntryIdOrderByIdDesc(1L))
                .map(ValidationResult::getId).contains(latest.getId());
        assertThat(validationResultRepository.findFirstByJournalEntryIdOrderByIdDesc(3L)).isEmpty();
    }
}
