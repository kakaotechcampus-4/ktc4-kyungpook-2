package com.itda.backend.repository.insight;

import java.time.LocalDate;

public record ApprovedInsightPeriod(Long childId, LocalDate periodFrom, LocalDate periodTo) {
}
