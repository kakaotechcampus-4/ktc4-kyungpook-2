package com.itda.backend.dto.response;

import com.itda.backend.domain.ChildStatus;

/**
 * POST /api/v1/institutions/me/children 의 응답. O-10 명부 항목과 필드가 같아도 따로 둔다 —
 * 등록과 함께 발급한 보호자 초대 코드({@code invitation})가 붙는다.
 */
public record RegisteredChildResponse(RegisteredChild child, IssuedInvitationResponse invitation) {

    public record RegisteredChild(String id, String name, String birthDate, String externalId, ChildStatus status) {
    }
}
