package com.itda.backend.dto.response.checklist;

import java.time.LocalDateTime;
import java.util.List;

public record SentChecklistResponse(
        String id,
        List<String> items,
        LocalDateTime sentAt,
        int total,
        int confirmed,
        List<Target> targets) {

    public record Target(ChildRef child, boolean onRoster, LocalDateTime confirmedAt) {
    }

    public record ChildRef(String id, String name) {
    }
}
