package com.itda.backend.dto.response.invitation;

import com.itda.backend.domain.child.ChildStatus;

/**
 * G-04 수락 결과. 연결만 됐을 뿐 동의는 아니다 — 수락은 아동 상태를 바꾸지 않고,
 * {@code childStatus} 는 아동의 실제 현재 상태다. 이미 다른 보호자의 동의로 ACTIVE 인 아이면 active 가 온다.
 *
 * <p>{@code organizationId} 는 코드를 발급한 기관이다. 보호자 화면은 수락 뒤 이 기관에 대한 동의 화면
 * (G-02 동의 미리보기)으로 넘어가는데, 그 요청에 기관 ID 가 필요하다.
 */
public record AcceptedInvitationResponse(
        String childId, String childName, String organizationId, String organizationName, ChildStatus childStatus) {
}
