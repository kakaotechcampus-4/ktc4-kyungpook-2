package com.itda.backend.repository.record;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.record.RawRecordUpload;

public interface RawRecordUploadRepository extends JpaRepository<RawRecordUpload, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from RawRecordUpload u where u.id = :id")
    Optional<RawRecordUpload> findByIdForUpdate(@Param("id") Long id);

    List<RawRecordUpload> findByCreatedAtBeforeOrderByIdAsc(LocalDateTime before, Limit limit);
}
