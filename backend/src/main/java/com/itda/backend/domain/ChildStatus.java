package com.itda.backend.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

// MatchingStatus와 같은 패턴: FE(frontend/app/lib/types.ts의 ChildStatus)가 소문자
// 문자열("active" 등)을 기대해서 JSON 직렬화만 소문자로 내보낸다. DB 저장은
// @Enumerated(STRING)이라 컬럼 값은 그대로 ACTIVE/... 이고 영향 없음.
public enum ChildStatus {
    PENDING_CONSENT,
    ACTIVE,
    SUSPENDED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static ChildStatus fromJson(String value) {
        return valueOf(value.toUpperCase());
    }
}
