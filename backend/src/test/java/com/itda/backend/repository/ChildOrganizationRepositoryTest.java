package com.itda.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.ChildOrganization;

@DataJpaTest
@ActiveProfiles("test")
class ChildOrganizationRepositoryTest {

    @Autowired
    private ChildOrganizationRepository childOrganizationRepository;

    @Test
    void 연결된_기관의_아동이면_소속으로_판단한다() {
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(1L, 10L));

        assertThat(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(1L, 10L))
                .isTrue();
        assertThat(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(1L, 20L))
                .isFalse();
    }

    @Test
    void 연결을_해제하면_소속이_아니다() {
        ChildOrganization link = childOrganizationRepository.saveAndFlush(ChildOrganization.of(1L, 10L));

        link.delete();
        childOrganizationRepository.saveAndFlush(link);

        assertThat(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(1L, 10L))
                .isFalse();
    }

    @Test
    void 연결_해제된_행도_찾아서_되살릴_수_있다() {
        ChildOrganization link = childOrganizationRepository.saveAndFlush(ChildOrganization.of(1L, 10L));
        link.delete();
        childOrganizationRepository.saveAndFlush(link);

        ChildOrganization found = childOrganizationRepository.findByChildIdAndOrganizationId(1L, 10L).orElseThrow();
        found.restore();
        childOrganizationRepository.saveAndFlush(found);

        assertThat(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(1L, 10L))
                .isTrue();
    }

    @Test
    void 같은_아동과_기관은_두_번_연결할_수_없다() {
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(1L, 10L));

        assertThatThrownBy(() -> childOrganizationRepository.saveAndFlush(ChildOrganization.of(1L, 10L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_기관에서_같은_관리번호는_DB가_막는다() {
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(1L, 10L, "2026-0031"));

        assertThatThrownBy(() -> childOrganizationRepository.saveAndFlush(ChildOrganization.of(2L, 10L, "2026-0031")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 다른_기관은_같은_관리번호를_쓸_수_있다() {
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(1L, 10L, "2026-0031"));
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(2L, 20L, "2026-0031"));

        assertThat(childOrganizationRepository.existsByOrganizationIdAndExternalId(10L, "2026-0031")).isTrue();
        assertThat(childOrganizationRepository.existsByOrganizationIdAndExternalId(20L, "2026-0031")).isTrue();
    }

    @Test
    void 관리번호가_없는_연결은_한_기관에_여럿_있을_수_있다() {
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(1L, 10L));
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(2L, 10L));

        assertThat(childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(10L)).hasSize(2);
    }

    @Test
    void 연결을_해제하면_그_관리번호를_다른_아동이_쓸_수_있다() {
        ChildOrganization link = childOrganizationRepository.saveAndFlush(ChildOrganization.of(1L, 10L, "2026-0031"));
        link.delete();
        childOrganizationRepository.saveAndFlush(link);

        assertThat(childOrganizationRepository.existsByOrganizationIdAndExternalId(10L, "2026-0031")).isFalse();
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(2L, 10L, "2026-0031"));
        assertThat(childOrganizationRepository.existsByOrganizationIdAndExternalId(10L, "2026-0031")).isTrue();
    }

    @Test
    void 해제된_연결은_기관_명부용_조회에서_빠진다() {
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(1L, 10L, "A-1"));
        ChildOrganization removed = childOrganizationRepository.saveAndFlush(ChildOrganization.of(2L, 10L, "A-2"));
        removed.delete();
        childOrganizationRepository.saveAndFlush(removed);

        assertThat(childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(10L))
                .extracting(ChildOrganization::getChildId)
                .containsExactly(1L);
    }
}
