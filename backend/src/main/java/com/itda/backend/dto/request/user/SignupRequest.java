package com.itda.backend.dto.request.user;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.itda.backend.domain.organization.OrganizationType;

public record SignupRequest(
        @NotNull
        @Pattern(regexp = "org|parent")
        String role,

        @Size(max = 100)
        String organizationName,

        OrganizationType organizationType,

        @Pattern(regexp = "^\\d{10}$")
        String businessNumber,

        @Size(max = 20)
        String handoverCode
) {

    public static final String ROLE_ORGANIZATION = "org";

    @JsonIgnore
    public boolean isOrganizationSignup() {
        return ROLE_ORGANIZATION.equals(role);
    }

    @JsonIgnore
    public boolean isHandoverSignup() {
        return isOrganizationSignup() && handoverCode != null;
    }

    @JsonIgnore
    @AssertTrue
    public boolean isOrganizationInfoMatchingRole() {
        boolean noOrganizationInfo = organizationName == null && organizationType == null && businessNumber == null;
        if (isHandoverSignup()) {
            return noOrganizationInfo && !handoverCode.isBlank();
        }
        if (isOrganizationSignup()) {
            return organizationName != null && !organizationName.isBlank()
                    && organizationType != null
                    && businessNumber != null;
        }
        return noOrganizationInfo && handoverCode == null;
    }
}
