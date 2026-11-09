package com.itda.backend.dto.response.invitation;

import com.itda.backend.domain.child.ChildStatus;

public record AcceptedInvitationResponse(
        String childId, String childName, String organizationId, String organizationName, ChildStatus childStatus) {
}
