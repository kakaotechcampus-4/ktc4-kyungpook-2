package com.itda.backend.dto.response;

import com.itda.backend.domain.ChildStatus;

/**
 * POST /api/v1/institutions/me/children 의 응답. O-10 명부 항목과 필드가 같아도 따로 둔다 —
 * 보호자 연결(초대 코드) 작업에서 {@code child} 옆에 {@code inviteCode} 가 붙을 자리다.
 */
public record RegisteredChildResponse(RegisteredChild child) {

    public record RegisteredChild(String id, String name, String birthDate, ChildStatus status) {
    }
}
