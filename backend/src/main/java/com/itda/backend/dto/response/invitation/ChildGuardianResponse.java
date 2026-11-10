package com.itda.backend.dto.response.invitation;

import java.time.OffsetDateTime;

public record ChildGuardianResponse(
        String userId, OffsetDateTime linkedAt, boolean linkedByThisOrganization, String invitationCode) {
}
