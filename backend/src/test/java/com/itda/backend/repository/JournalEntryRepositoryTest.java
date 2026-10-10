package com.itda.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;

@DataJpaTest
@ActiveProfiles("test")
class JournalEntryRepositoryTest {

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void 매칭_전_일지는_아동_없이_대기_상태로_저장된다() {
        JournalEntry saved = journalEntryRepository.saveAndFlush(
                JournalEntry.of(3L, LocalDate.of(2026, 9, 1), "점심시간에 식사를 잘함", 1));
        entityManager.clear();

        JournalEntry found = journalEntryRepository.findByIdAndDeletedAtIsNull(saved.getId()).orElseThrow();
        assertThat(found.getRawRecordId()).isEqualTo(3L);
        assertThat(found.getContent()).isEqualTo("점심시간에 식사를 잘함");
        assertThat(found.getSequenceNo()).isEqualTo(1);
        assertThat(found.getChildId()).isNull();
        assertThat(found.getStatus()).isEqualTo(JournalEntryStatus.PENDING);
    }

    @Test
    void 원본_파일_없이_직접_입력한_일지도_저장된다() {
        JournalEntry saved = journalEntryRepository.saveAndFlush(
                JournalEntry.of(null, null, "큰 소리에 귀를 막음", null));

        assertThat(journalEntryRepository.findByIdAndDeletedAtIsNull(saved.getId())).isPresent();
    }

    @Test
    void 매칭이_확정되면_아동이_채워진다() {
        JournalEntry entry = journalEntryRepository.saveAndFlush(
                JournalEntry.of(3L, LocalDate.of(2026, 9, 1), "점심시간에 식사를 잘함", 1));

        entry.assignChild(8L);
        journalEntryRepository.saveAndFlush(entry);
        entityManager.clear();

        assertThat(journalEntryRepository.findByIdAndDeletedAtIsNull(entry.getId()).orElseThrow().getChildId())
                .isEqualTo(8L);
    }

    @Test
    void 삭제_표시한_일지는_조회되지_않는다() {
        JournalEntry entry = journalEntryRepository.saveAndFlush(
                JournalEntry.of(3L, LocalDate.of(2026, 9, 1), "점심시간에 식사를 잘함", 1));

        entry.delete();
        journalEntryRepository.saveAndFlush(entry);

        assertThat(journalEntryRepository.findByIdAndDeletedAtIsNull(entry.getId())).isEmpty();
    }

    private JournalEntry save(Long rawRecordId) {
        return journalEntryRepository.saveAndFlush(JournalEntry.of(rawRecordId, LocalDate.of(2026, 9, 1), "기록", 1));
    }

    @Test
    void 매칭_대상은_원본_파일이_있는_대기_일지를_id_순으로_가져온다() {
        JournalEntry first = save(3L);
        JournalEntry second = save(3L);
        save(null); // 직접 입력 — 매칭 단계를 건너뛴다 (DB 스키마 §6.2)
        JournalEntry deleted = save(3L);
        deleted.delete();
        JournalEntry alreadyMatching = save(3L);
        alreadyMatching.startMatching();
        journalEntryRepository.saveAllAndFlush(List.of(deleted, alreadyMatching));
        entityManager.clear();

        List<JournalEntry> targets = journalEntryRepository.findMatchingTargets(JournalEntryStatus.PENDING, Limit.of(10));

        assertThat(targets).extracting(JournalEntry::getId).containsExactly(first.getId(), second.getId());
    }

    @Test
    void 매칭_대상은_한_번에_정해진_개수만_가져온다() {
        save(3L);
        save(3L);
        save(3L);

        assertThat(journalEntryRepository.findMatchingTargets(JournalEntryStatus.PENDING, Limit.of(2))).hasSize(2);
    }

    @Test
    void 매칭_중에_멈춘_일지를_찾을_수_있다() {
        save(3L);
        JournalEntry stuck = save(3L);
        stuck.startMatching();
        journalEntryRepository.saveAndFlush(stuck);
        entityManager.clear();

        assertThat(journalEntryRepository.findByStatusAndDeletedAtIsNull(JournalEntryStatus.MATCHING))
                .extracting(JournalEntry::getId).containsExactly(stuck.getId());
    }

    private JournalEntry saveMatched(Long rawRecordId) {
        JournalEntry entry = save(rawRecordId);
        entry.startMatching();
        entry.confirmMatch(8L);
        return journalEntryRepository.saveAndFlush(entry);
    }

    @Test
    void 검증_대상은_매칭이_확정된_일지를_id_순으로_가져온다() {
        JournalEntry first = saveMatched(3L);
        JournalEntry second = saveMatched(3L);
        save(3L); // 아직 매칭 전
        JournalEntry deleted = saveMatched(3L);
        deleted.delete();
        JournalEntry alreadyValidating = saveMatched(3L);
        alreadyValidating.startValidating();
        journalEntryRepository.saveAllAndFlush(List.of(deleted, alreadyValidating));
        entityManager.clear();

        List<JournalEntry> targets = journalEntryRepository.findValidationTargets(JournalEntryStatus.MATCHED, Limit.of(10));

        assertThat(targets).extracting(JournalEntry::getId).containsExactly(first.getId(), second.getId());
    }

    @Test
    void 검증_대상은_한_번에_정해진_개수만_가져온다() {
        saveMatched(3L);
        saveMatched(3L);
        saveMatched(3L);

        assertThat(journalEntryRepository.findValidationTargets(JournalEntryStatus.MATCHED, Limit.of(2))).hasSize(2);
    }

    @Test
    void 아동이_비어_있는_일지는_검증_대상에서_뺀다() {
        JournalEntry withoutChild = saveMatched(3L);
        // 정상 경로로는 생기지 않는다. 판정 대상 없이 보내면 AI 가 REVIEW(대상불명확)로 흘려보내므로 막아 둔다.
        entityManager.getEntityManager()
                .createNativeQuery("update journal_entry set child_id = null where id = :id")
                .setParameter("id", withoutChild.getId())
                .executeUpdate();
        entityManager.clear();

        assertThat(journalEntryRepository.findValidationTargets(JournalEntryStatus.MATCHED, Limit.of(10))).isEmpty();
    }
}
