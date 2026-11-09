package com.itda.backend.service.approval;

import java.util.Collection;
import java.util.stream.Collectors;

import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.summary.SummaryResult;

public final class Gate1Version {

    private Gate1Version() {
    }

    public static String of(SummaryResult summary, Collection<JournalEntry> entries) {
        String entryIds = entries.stream()
                .map(JournalEntry::getId)
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        return summary.getUpdatedAt() + "/" + entryIds;
    }
}
