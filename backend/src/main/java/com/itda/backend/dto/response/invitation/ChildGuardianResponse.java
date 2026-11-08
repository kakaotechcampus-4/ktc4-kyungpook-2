package com.itda.backend.dto.response.invitation;

import java.time.OffsetDateTime;

/**
 * O-18 아이와 연결된 보호자 한 명. {@code linkedByThisOrganization} 이 참인 보호자만 이 기관이 해제할 수 있다.
 * 보호자 이름은 저장하지 않는다 — 화면이 "아이 이름 + 보호자" 로 쓴다.
 */
public record ChildGuardianResponse(
        String userId, OffsetDateTime linkedAt, boolean linkedByThisOrganization) {
}
