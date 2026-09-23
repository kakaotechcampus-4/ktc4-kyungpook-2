package com.itda.backend.dto;

// action: "assign"(childId 필수) 또는 "not_ours" (docs/api/api-spec.md O-23)
public record MatchingResolveRequest(String action, Long childId) {
}
