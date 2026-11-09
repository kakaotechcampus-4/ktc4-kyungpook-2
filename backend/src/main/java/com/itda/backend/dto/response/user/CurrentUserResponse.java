package com.itda.backend.dto.response.user;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.domain.user.UserRole;

/**
 * GET /api/v1/auth/me 의 응답. 프론트 라우트 가드가 화면마다 호출한다.
 *
 * <p>PK 는 Long 이지만 문자열로 내린다(팀 합의). role 은 프론트 타입에 맞춰 소문자다.
 *
 * <p>{@code signupCompleted} 는 원시 boolean 이라 NON_NULL 설정과 무관하게 항상 나간다.
 * 프론트는 이 값만 보고 가입 화면으로 보낼지 정한다. 가입 미완료면 role·institutionId·institutionName 이 없다.
 *
 * <p>{@code institutionName} 은 사이드바에 기관명을 띄우려고 둔다(#236). {@code institutionId} 와 같이 기관 회원만 있다.
 *
 * <p>{@code termsAgreed} 도 원시 boolean 이라 늘 나간다. 회원이 자기 역할의 서비스 약관에 동의했는지다 — false 면
 * 프론트가 약관 화면(보호자 · 기관 따로)으로 보내고, 서버도 보호 API 를 403 TERMS_NOT_AGREED 로 막는다.
 * 가입 미완료는 false 다.
 *
 * <p>{@code @JsonInclude} 를 여기에 직접 붙여야 한다 — ApiResponse 에 붙은 설정은
 * 중첩된 타입까지 내려가지 않는다. 이게 없으면 보호자 응답에 {@code "institutionId": null} 이
 * 그대로 나가서 "role 이 org 일 때만 포함한다" 는 계약이 깨진다. {@code institutionName} 도 같다.
 *
 * <p>{@code withdrawal} 은 늘 나간다. {@code none} 이면 진행 중인 탈퇴가 없고 {@code in_progress} 면 탈퇴를 처리하는
 * 중이다 — 프론트는 가입 · 약관 · 아이 목록보다 먼저 이 값을 보고 탈퇴 안내 화면으로 보낸다. 탈퇴를 마친 회원은 이 응답 대신
 * 401 ACCOUNT_WITHDRAWN 이다. {@code withdrawalEnabled} 도 늘 나간다 — 서버가 탈퇴를 열었는지라, false 면 화면이 탈퇴 버튼을 숨긴다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrentUserResponse(
        String role, String userId, String name, String institutionId, String institutionName,
        boolean signupCompleted, boolean termsAgreed, String withdrawal, boolean withdrawalEnabled) {

    public static final String WITHDRAWAL_NONE = "none";
    public static final String WITHDRAWAL_IN_PROGRESS = "in_progress";

    /**
     * @param organization 회원이 속한 기관. 기관 회원이 아니거나 찾지 못했으면 null 이고, 기관명이 응답에서 빠진다
     * @param termsAgreed  {@code TermsService.isAgreed} 의 판정
     */
    public static CurrentUserResponse from(User user, Organization organization, boolean termsAgreed) {
        String institutionId = toApiInstitutionId(user);
        return new CurrentUserResponse(
                toApiRole(user.getRole()),
                String.valueOf(user.getId()),
                user.getName(),
                institutionId,
                institutionId == null || organization == null ? null : organization.getName(),
                user.isSignupCompleted(),
                termsAgreed,
                user.isWithdrawing() ? WITHDRAWAL_IN_PROGRESS : WITHDRAWAL_NONE,
                false);
    }

    /** 탈퇴를 열었는지 담는다. 회원이 아니라 서버 설정의 값이라 서비스가 따로 채운다(WithdrawalService.isEnabled). */
    public CurrentUserResponse withWithdrawalEnabled(boolean enabled) {
        return new CurrentUserResponse(role, userId, name, institutionId, institutionName, signupCompleted, termsAgreed,
                withdrawal, enabled);
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
