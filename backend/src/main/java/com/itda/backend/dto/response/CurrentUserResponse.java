package com.itda.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.itda.backend.domain.User;
import com.itda.backend.domain.UserRole;

/**
 * GET /api/v1/auth/me 의 응답. 프론트 라우트 가드가 화면마다 호출한다.
 *
 * <p>PK 는 Long 이지만 문자열로 내린다(팀 합의). role 은 프론트 타입에 맞춰 소문자다.
 *
 * <p>{@code signupCompleted} 는 원시 boolean 이라 NON_NULL 설정과 무관하게 항상 나간다.
 * 프론트는 이 값만 보고 가입 화면으로 보낼지 정한다. 가입 미완료면 role·institutionId 가 없다.
 *
 * <p>{@code @JsonInclude} 를 여기에 직접 붙여야 한다 — ApiResponse 에 붙은 설정은
 * 중첩된 타입까지 내려가지 않는다. 이게 없으면 보호자 응답에 {@code "institutionId": null} 이
 * 그대로 나가서 "role 이 org 일 때만 포함한다" 는 계약이 깨진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrentUserResponse(
        String role, String userId, String name, String institutionId, boolean signupCompleted) {

    public static CurrentUserResponse from(User user) {
        return new CurrentUserResponse(
                toApiRole(user.getRole()),
                String.valueOf(user.getId()),
                user.getName(),
                toApiInstitutionId(user),
                user.isSignupCompleted());
    }

    /** 가입 미완료 회원은 역할이 없다. null 이면 NON_NULL 설정으로 응답에서 빠진다. */
    private static String toApiRole(UserRole role) {
        if (role == null) {
            return null;
        }
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
