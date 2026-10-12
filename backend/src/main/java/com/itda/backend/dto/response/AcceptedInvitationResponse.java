package com.itda.backend.dto.response;

import com.itda.backend.domain.ChildStatus;

/**
 * G-04 수락 결과. 연결만 됐을 뿐 동의는 아니다 — 수락은 아동 상태를 바꾸지 않고,
 * {@code childStatus} 는 아동의 실제 현재 상태다. 이미 다른 보호자의 동의로 ACTIVE 인 아이면 active 가 온다.
 */
public record AcceptedInvitationResponse(
        String childId, String childName, String organizationName, ChildStatus childStatus) {
}
