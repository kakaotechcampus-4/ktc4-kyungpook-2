package com.itda.backend.repository.record;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.record.RawRecord;

public interface RawRecordRepository extends JpaRepository<RawRecord, Long> {

    List<RawRecord> findByInstitutionIdAndDeletedAtIsNull(String institutionId);

    Optional<RawRecord> findByIdAndDeletedAtIsNull(Long id);
}
