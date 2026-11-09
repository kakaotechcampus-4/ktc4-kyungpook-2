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

    /**
     * 완료를 한 줄로 세운다 (#450). 같은 접수의 완료가 동시에 두 번 와도 뒤 요청은 앞 요청이 남긴 원본 기록을 보고 새로 만들지
     * 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from RawRecordUpload u where u.id = :id")
    Optional<RawRecordUpload> findByIdForUpdate(@Param("id") Long id);

    /** 정리 대상. 오래된 것부터 정해진 수만큼 — 한 번에 다 못 지우면 이어서 부른다. */
    List<RawRecordUpload> findByCreatedAtBeforeOrderByIdAsc(LocalDateTime before, Limit limit);
}
