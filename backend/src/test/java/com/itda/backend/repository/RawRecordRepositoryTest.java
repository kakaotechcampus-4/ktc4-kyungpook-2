package com.itda.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;

@DataJpaTest
@ActiveProfiles("test")
class RawRecordRepositoryTest {

    @Autowired
    private RawRecordRepository rawRecordRepository;

    @Test
    void 새_원본은_수정_시각이_채워지고_힌트_없이_저장된다() {
        RawRecord saved = rawRecordRepository.saveAndFlush(record("7"));

        RawRecord found = rawRecordRepository.findByIdAndDeletedAtIsNull(saved.getId()).orElseThrow();
        assertThat(found.getUpdatedAt()).isEqualTo(found.getCreatedAt());
        assertThat(found.getHintName()).isNull();
        assertThat(found.getHintBirthdate()).isNull();
        assertThat(found.isDeleted()).isFalse();
    }

    @Test
    void 삭제_표시한_원본은_단건_조회와_기관_목록에서_빠진다() {
        RawRecord kept = rawRecordRepository.saveAndFlush(record("7"));
        RawRecord deleted = rawRecordRepository.saveAndFlush(record("7"));

        deleted.delete();
        rawRecordRepository.saveAndFlush(deleted);

        assertThat(rawRecordRepository.findByIdAndDeletedAtIsNull(deleted.getId())).isEmpty();
        assertThat(rawRecordRepository.findByInstitutionIdAndDeletedAtIsNull("7"))
                .extracting(RawRecord::getId)
                .containsExactly(kept.getId());
    }

    @Test
    void 삭제_표시를_되돌리면_다시_조회된다() {
        RawRecord record = rawRecordRepository.saveAndFlush(record("7"));
        record.delete();
        rawRecordRepository.saveAndFlush(record);

        record.restore();
        rawRecordRepository.saveAndFlush(record);

        assertThat(rawRecordRepository.findByIdAndDeletedAtIsNull(record.getId())).isPresent();
    }

    private RawRecord record(String institutionId) {
        return new RawRecord(institutionId, "journal.pdf", "kept/journal.pdf", "application/pdf", 12,
                RawRecordStatus.PENDING);
    }
}
