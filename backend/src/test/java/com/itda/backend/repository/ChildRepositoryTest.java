package com.itda.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildStatus;

@DataJpaTest
@ActiveProfiles("test")
class ChildRepositoryTest {

    @Autowired
    private ChildRepository childRepository;

    @Test
    void 새로_등록한_아동은_동의_대기_상태로_저장된다() {
        Child saved = childRepository.saveAndFlush(Child.of("임유진", LocalDate.of(2019, 11, 26)));

        Child found = childRepository.findByIdAndDeletedAtIsNull(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("임유진");
        assertThat(found.getBirthdate()).isEqualTo(LocalDate.of(2019, 11, 26));
        assertThat(found.getStatus()).isEqualTo(ChildStatus.PENDING_CONSENT);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 삭제_표시한_아동은_조회되지_않는다() {
        Child child = childRepository.saveAndFlush(Child.of("임유진", LocalDate.of(2019, 11, 26)));

        child.delete();
        childRepository.saveAndFlush(child);

        assertThat(childRepository.findByIdAndDeletedAtIsNull(child.getId())).isEmpty();
        assertThat(childRepository.findById(child.getId())).isPresent();
    }

    @Test
    void 생년월일_없이는_아동을_만들_수_없다() {
        assertThatThrownBy(() -> Child.of("임유진", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
