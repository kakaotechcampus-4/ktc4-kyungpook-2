package com.itda.backend.service.approval;

import java.time.LocalDate;

import com.itda.backend.domain.summary.SummaryStatus;

public record Gate1BoardItem(Long id, Long childId, String childName, LocalDate entryDate, SummaryStatus status,
        boolean processing, boolean noContent) {
}
