package com.itda.backend.exception.agent;

import com.itda.backend.domain.record.JournalEntry;

public class AgentContentTooLongException extends RuntimeException {

    public AgentContentTooLongException(Long journalEntryId, int length) {
        super("journal entry content too long to send to agent journalEntryId=" + journalEntryId
                + " length=" + length + " max=" + JournalEntry.MAX_CONTENT_LENGTH);
    }
}
