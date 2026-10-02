package com.itda.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.ValidationResult;

public interface ValidationResultRepository extends JpaRepository<ValidationResult, Long> {
}
