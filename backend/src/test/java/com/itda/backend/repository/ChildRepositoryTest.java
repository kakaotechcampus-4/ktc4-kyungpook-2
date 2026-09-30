package com.itda.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildOrganization;
import com.itda.backend.domain.ChildStatus;

@DataJpaTest
@ActiveProfiles("test")
class ChildRepositoryTest {

    @Autowired
    private ChildRepository childRepository;

    @Autowired
    private ChildOrganizationRepository childOrganizationRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void 새로_등록한_아동은_동의_대기_상태로_저장된다() {
        Child saved = childRepository.saveAndFlush(Child.of("임유진", LocalDate.of(2019, 11, 26)));
        entityManager.clear();

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

    @Test
    void 소속_기관의_아동만_명부에_나온다() {
        Child ours = childRepository.saveAndFlush(Child.of("임유진", LocalDate.of(2019, 11, 26)));
        Child theirs = childRepository.saveAndFlush(Child.of("박서연", LocalDate.of(2020, 5, 5)));
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(ours.getId(), 1L));
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(theirs.getId(), 2L));
        entityManager.clear();

        List<Child> roster = childRepository.findByOrganizationId(1L);

        assertThat(roster).extracting(Child::getName).containsExactly("임유진");
    }

    @Test
    void 연결_해제된_아동은_명부에서_빠진다() {
        Child child = childRepository.saveAndFlush(Child.of("임유진", LocalDate.of(2019, 11, 26)));
        ChildOrganization link = childOrganizationRepository.saveAndFlush(ChildOrganization.of(child.getId(), 1L));
        link.delete();
        childOrganizationRepository.saveAndFlush(link);
        entityManager.clear();

        assertThat(childRepository.findByOrganizationId(1L)).isEmpty();
    }

    /** 화면용 명부(findByOrganizationId)는 동의 전 아동도 포함해야 한다 — 화면에서 상태별로 걸러야 하니까. */
    @Test
    void 화면용_명부는_동의_전_아동도_포함한다() {
        Child child = childRepository.saveAndFlush(Child.of("임유진", LocalDate.of(2019, 11, 26)));
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(child.getId(), 1L));
        entityManager.clear();

        assertThat(childRepository.findByOrganizationId(1L)).extracting(Child::getName).containsExactly("임유진");
    }

    /** AI 매칭 명부(findActiveByOrganizationId)는 동의 전 아동을 반드시 제외해야 한다 — 동의 없는 아이로 매칭되면 안 되니까. */
    @Test
    void 매칭_명부는_동의_전_아동을_제외한다() {
        Child pending = childRepository.saveAndFlush(Child.of("임유진", LocalDate.of(2019, 11, 26)));
        Child active = childRepository.saveAndFlush(Child.of("박서연", LocalDate.of(2020, 5, 5)));
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(pending.getId(), 1L));
        childOrganizationRepository.saveAndFlush(ChildOrganization.of(active.getId(), 1L));
        entityManager.getEntityManager()
                .createQuery("update Child c set c.status = com.itda.backend.domain.ChildStatus.ACTIVE where c.id = :id")
                .setParameter("id", active.getId())
                .executeUpdate();
        entityManager.clear();

        List<Child> roster = childRepository.findActiveByOrganizationId(1L);

        assertThat(roster).extracting(Child::getName).containsExactly("박서연");
    }
}
