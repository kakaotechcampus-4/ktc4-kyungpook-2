package com.itda.backend.domain;

// ponytail: ERD의 matching_result.status는 VARCHAR(30)로만 정의돼있고 값은 안 정해져 있어서,
// 실제 Matching Agent 계약(AI/matching/schemas.py의 MatchStatus)을 그대로 따른다.
public enum MatchingStatus {
    AUTO,
    REVIEW,
    MULTI,
    UNMATCHED
}
