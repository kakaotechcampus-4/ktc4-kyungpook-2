package com.itda.backend.repository.record;

import java.time.LocalDate;

public record ProgressEntryKey(Long id, Long childId, LocalDate entryDate, String institutionId) {
}
