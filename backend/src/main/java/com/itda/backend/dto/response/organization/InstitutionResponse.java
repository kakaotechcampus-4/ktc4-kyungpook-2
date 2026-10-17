package com.itda.backend.dto.response.organization;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;

/**
 * api-spec §3 의 {@code Institution} 공통 모델. 화면이 쓰는 유형 이름은 DB enum 과 다르다
 * ({@code ACTIVITY_SUPPORT} → {@code assistant}).
 *
 * <p>{@code verified} 는 언제나 false 다. 사업자등록번호는 형식만 보고 진위는 확인하지 않으므로
 * (Organization 주석) "확인된 기관"이라고 내려보내면 사실이 아니다.
 */
public record InstitutionResponse(String id, String name, String type, boolean verified) {

    public static InstitutionResponse from(Organization organization) {
        return new InstitutionResponse(
                String.valueOf(organization.getId()), organization.getName(), typeOf(organization.getType()), false);
    }

    private static String typeOf(OrganizationType type) {
        return switch (type) {
            case SCHOOL -> "school";
            case CENTER -> "center";
            case ACTIVITY_SUPPORT -> "assistant";
        };
    }
}
