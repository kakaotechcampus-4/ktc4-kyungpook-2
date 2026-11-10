package com.itda.backend.repository.insight;

import java.time.LocalDate;

import com.itda.backend.domain.insight.InsightStatus;

public record InsightKey(Long id, Long childId, LocalDate periodFrom, LocalDate periodTo, Integer revision,
        InsightStatus status, String sourceInstitutionIds) {
}
