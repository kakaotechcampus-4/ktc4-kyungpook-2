package com.itda.backend.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

// ponytail: ERD의 matching_result.status는 VARCHAR(30)로만 정의돼있고 값은 안 정해져 있어서,
// 실제 Matching Agent 계약(AI/matching/schemas.py의 MatchStatus)을 그대로 따른다.
// FE 타입(frontend/app/lib/types.ts의 MatchStatus)이 소문자 문자열을 기대해서
// JSON 직렬화만 소문자로 내보낸다 — DB 저장은 @Enumerated(STRING)이라 컬럼 값은
// 그대로 AUTO/REVIEW/... 이고 영향 없음.
public enum MatchingStatus {
    AUTO,
    REVIEW,
    MULTI,
    UNMATCHED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static MatchingStatus fromJson(String value) {
        return valueOf(value.toUpperCase());
    }
}
