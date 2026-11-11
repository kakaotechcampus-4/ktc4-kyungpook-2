package com.itda.backend.repository.summary;

import java.time.LocalDate;

import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;

public record Gate1Key(Long id, Long childId, LocalDate entryDate, Integer revision, SummaryStatus status,
        boolean noContent) {

    public static Gate1Key of(SummaryResult summary) {
        return new Gate1Key(summary.getId(), summary.getChildId(), summary.getEntryDate(), summary.getRevision(),
                summary.getStatus(), summary.isNoContent());
    }
}
