package com.itda.backend.repository.summary;

import java.time.LocalDate;

public record LatestRevision(Long childId, LocalDate entryDate, Integer revision) {
}
