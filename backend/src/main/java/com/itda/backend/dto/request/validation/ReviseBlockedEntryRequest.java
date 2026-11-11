package com.itda.backend.dto.request.validation;

import com.itda.backend.domain.record.JournalEntry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviseBlockedEntryRequest(
        @NotBlank
        @Size(max = JournalEntry.MAX_CONTENT_LENGTH)
        String content) {
}
