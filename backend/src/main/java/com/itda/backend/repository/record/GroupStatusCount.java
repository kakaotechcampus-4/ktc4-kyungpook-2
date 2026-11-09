package com.itda.backend.repository.record;

import java.time.LocalDate;

import com.itda.backend.domain.record.JournalEntryStatus;

public record GroupStatusCount(Long childId, LocalDate entryDate, JournalEntryStatus status, long count) {
}
