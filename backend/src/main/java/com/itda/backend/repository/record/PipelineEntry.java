package com.itda.backend.repository.record;

import java.time.LocalDate;

import com.itda.backend.domain.record.FailureStage;
import com.itda.backend.domain.record.JournalEntryStatus;

public record PipelineEntry(Long id, Long childId, LocalDate entryDate, JournalEntryStatus status,
        FailureStage failureStage) {
}
