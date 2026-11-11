package com.itda.backend.service.summary;

import java.time.LocalDateTime;
import java.util.List;

public record ClaimedSummaryGroup(SummaryGroup group, List<Long> journalEntryIds, LocalDateTime claimedAt) {
}
