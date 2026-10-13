package com.itda.backend.dto.response.invitation;

import java.time.OffsetDateTime;

/**
 * O-18 아이와 연결된 보호자 한 명. {@code linkedByThisOrganization} 이 참인 보호자만 이 기관이 해제할 수 있다.
 * {@code name} 은 카카오 닉네임이라 동의하지 않았으면 null 이다.
 */
public record ChildGuardianResponse(
        String userId, String name, OffsetDateTime linkedAt, boolean linkedByThisOrganization) {
}
