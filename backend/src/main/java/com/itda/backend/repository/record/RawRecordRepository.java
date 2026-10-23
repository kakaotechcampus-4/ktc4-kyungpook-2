package com.itda.backend.repository.record;

import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.record.RawRecord;

public interface RawRecordRepository extends JpaRepository<RawRecord, Long> {

    List<RawRecord> findByInstitutionIdAndDeletedAtIsNull(String institutionId);

    /**
     * 처리 현황(O-26)용. 최근 업로드가 먼저 온다 — id 는 단조 증가라 업로드 순서와 같다.
     *
     * <p>개수를 받는 이유는 이 목록이 파일마다 일지를 또 끌고 오기 때문이다. 상한이 없으면 한 학기 치가
     * 한 응답에 담기는데, 화면(I-03·I-05)은 대시보드라 자주 부른다.
     */
    List<RawRecord> findByInstitutionIdAndDeletedAtIsNullOrderByIdDesc(String institutionId, Limit limit);

    Optional<RawRecord> findByIdAndDeletedAtIsNull(Long id);
}
