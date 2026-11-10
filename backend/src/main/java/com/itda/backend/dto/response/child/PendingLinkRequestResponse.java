package com.itda.backend.dto.response.child;

import java.time.OffsetDateTime;
import java.util.List;

public record PendingLinkRequestResponse(
        RequestedChild child, int requesterCount, OffsetDateTime requestedAt) {

    public record RequestedChild(String id, String name, String birthDate) {
    }
}
