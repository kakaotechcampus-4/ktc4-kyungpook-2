package com.itda.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.itda.backend.domain.User;
import com.itda.backend.domain.UserRole;

/**
 * GET /api/v1/auth/me 의 응답. 프론트 라우트 가드가 화면마다 호출한다.
 *
 * <p>PK 는 Long 이지만 문자열로 내린다(팀 합의). role 은 프론트 타입에 맞춰 소문자다.
 *
 * <p>{@code @JsonInclude} 를 여기에 직접 붙여야 한다 — ApiResponse 에 붙은 설정은
 * 중첩된 타입까지 내려가지 않는다. 이게 없으면 보호자 응답에 {@code "institutionId": null} 이
 * 그대로 나가서 "role 이 org 일 때만 포함한다" 는 계약이 깨진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrentUserResponse(String role, String userId, String name, String institutionId) {

    public static CurrentUserResponse from(User user) {
        return new CurrentUserResponse(
                toApiRole(user.getRole()),
                String.valueOf(user.getId()),
                user.getName(),
                toApiInstitutionId(user));
    }

    private static String toApiRole(UserRole role) {
        return switch (role) {
            case ORGANIZATION -> "org";
            case PARENT -> "parent";
        };
    }

    private static String toApiInstitutionId(User user) {
        if (!user.isOrganization() || user.getOrganizationId() == null) {
            return null;
        }
        return String.valueOf(user.getOrganizationId());
    }
}
