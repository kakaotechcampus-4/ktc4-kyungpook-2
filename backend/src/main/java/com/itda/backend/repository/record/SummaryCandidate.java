package com.itda.backend.repository.record;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record SummaryCandidate(Long journalEntryId, Long childId, LocalDate entryDate, String institutionId,
        LocalDateTime createdAt) {
}
