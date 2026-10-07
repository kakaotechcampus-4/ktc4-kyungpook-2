package com.itda.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.ValidationResult;
import com.itda.backend.domain.ValidationVerdict;

public interface ValidationResultRepository extends JpaRepository<ValidationResult, Long> {

    // 수정 요청 큐(O-24). 같은 일지를 다시 검증하면 행이 쌓이므로(§8.1) 최신 것이 먼저 오게 정렬한다.
    // 정렬만으로는 중복이 걸러지지 않는다 — 일지당 한 건으로 줄이는 것, 소속 기관 필터,
    // "지금도 막힌 상태인지" 확인은 모두 ValidationQueueService 가 한다.
    List<ValidationResult> findByVerdictOrderByIdDesc(ValidationVerdict verdict);
}
