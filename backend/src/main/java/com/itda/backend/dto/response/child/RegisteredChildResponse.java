package com.itda.backend.dto.response.child;

import com.itda.backend.domain.child.ChildStatus;
import com.itda.backend.dto.response.invitation.IssuedInvitationResponse;

public record RegisteredChildResponse(RegisteredChild child, IssuedInvitationResponse invitation) {

    public record RegisteredChild(String id, String name, String birthDate, String externalId, ChildStatus status) {
    }
}
