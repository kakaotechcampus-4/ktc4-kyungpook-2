package com.itda.backend.dto.response.user;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.domain.user.UserRole;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrentUserResponse(
        String role, String userId, String institutionId, String institutionName,
        boolean signupCompleted, boolean termsAgreed, String withdrawal, boolean withdrawalEnabled) {

    public static final String WITHDRAWAL_NONE = "none";
    public static final String WITHDRAWAL_IN_PROGRESS = "in_progress";

    public static CurrentUserResponse from(User user, Organization organization, boolean termsAgreed) {
        String institutionId = toApiInstitutionId(user);
        return new CurrentUserResponse(
                toApiRole(user.getRole()),
                String.valueOf(user.getId()),
                institutionId,
                institutionId == null || organization == null ? null : organization.getName(),
                user.isSignupCompleted(),
                termsAgreed,
                user.isWithdrawing() ? WITHDRAWAL_IN_PROGRESS : WITHDRAWAL_NONE,
                false);
    }

    public CurrentUserResponse withWithdrawalEnabled(boolean enabled) {
        return new CurrentUserResponse(role, userId, institutionId, institutionName, signupCompleted, termsAgreed,
                withdrawal, enabled);
    }

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
