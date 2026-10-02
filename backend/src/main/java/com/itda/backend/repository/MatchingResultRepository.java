package com.itda.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;

public interface MatchingResultRepository extends JpaRepository<MatchingResult, Long> {

    List<MatchingResult> findByStatusNot(MatchingStatus status);
}
