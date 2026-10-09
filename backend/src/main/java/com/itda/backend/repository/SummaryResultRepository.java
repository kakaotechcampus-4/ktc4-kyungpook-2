package com.itda.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.SummaryResult;

public interface SummaryResultRepository extends JpaRepository<SummaryResult, Long> {
}
