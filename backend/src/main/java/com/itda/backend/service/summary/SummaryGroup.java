package com.itda.backend.service.summary;

import java.time.LocalDate;

public record SummaryGroup(Long childId, LocalDate entryDate, Long institutionId) {
}
