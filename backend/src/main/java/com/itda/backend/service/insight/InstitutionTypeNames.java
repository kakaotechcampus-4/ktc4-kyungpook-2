package com.itda.backend.service.insight;

import java.util.Map;

import com.itda.backend.domain.organization.OrganizationType;

public final class InstitutionTypeNames {

    private static final Map<OrganizationType, String> NAMES = Map.of(
            OrganizationType.SCHOOL, "학교",
            OrganizationType.CENTER, "센터",
            OrganizationType.ACTIVITY_SUPPORT, "활동지원사");

    private InstitutionTypeNames() {
    }

    public static String of(OrganizationType type) {
        String name = NAMES.get(type);
        if (name == null) {
            throw new IllegalStateException(
                    "인사이트가 쓸 기관 종류 이름이 없습니다. AI/insight/config.py 와 맞춰 주세요: " + type);
        }
        return name;
    }
}
