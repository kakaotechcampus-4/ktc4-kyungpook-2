package com.itda.backend.dto.response.child;

import java.time.LocalDate;

public record OrganizationTimelineEntryResponse(
        String id,
        LocalDate date,
        String content,
        boolean edited,
        int sourceCount) {
}
