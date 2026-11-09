package com.itda.backend.dto.response.organization;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;

public record InstitutionResponse(String id, String name, String type, boolean verified) {

    public static InstitutionResponse from(Organization organization) {
        return new InstitutionResponse(
                String.valueOf(organization.getId()), organization.getName(), typeOf(organization.getType()), false);
    }

    public static InstitutionResponse fromNullable(Organization organization) {
        return organization == null ? null : from(organization);
    }

    private static String typeOf(OrganizationType type) {
        return switch (type) {
            case SCHOOL -> "school";
            case CENTER -> "center";
            case ACTIVITY_SUPPORT -> "assistant";
        };
    }
}
