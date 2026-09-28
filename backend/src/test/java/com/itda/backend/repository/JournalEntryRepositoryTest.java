package com.itda.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
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
}
