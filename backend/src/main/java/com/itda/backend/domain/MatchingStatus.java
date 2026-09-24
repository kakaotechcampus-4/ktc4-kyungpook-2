package com.itda.backend.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

// ponytail: ERD의 matching_result.status는 VARCHAR(30)로만 정의돼있고 값은 안 정해져 있어서,
// 실제 Matching Agent 계약(AI/matching/schemas.py의 MatchStatus)을 그대로 따른다.
// FE 타입(frontend/app/lib/types.ts의 MatchStatus)이 소문자 문자열을 기대해서
// JSON 직렬화만 소문자로 내보낸다 — DB 저장은 @Enumerated(STRING)이라 컬럼 값은
// 그대로 AUTO/REVIEW/... 이고 영향 없음.
// FAILED: AI 계약(schemas.py)엔 없는, DB수정본(9/24) §7.1 기준 BE 전용 상태값 —
// AI 서비스 호출 자체가 실패했을 때(네트워크 오류 등) BE가 이 값으로 기록한다.
public enum MatchingStatus {
    AUTO,
    REVIEW,
    MULTI,
    UNMATCHED,
    FAILED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static MatchingStatus fromJson(String value) {
        return valueOf(value.toUpperCase());
    }
}
