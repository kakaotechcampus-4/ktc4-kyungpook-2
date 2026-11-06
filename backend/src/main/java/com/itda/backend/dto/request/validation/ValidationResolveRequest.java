package com.itda.backend.dto.request.validation;

// action: "hold"(보류)만 받는다 — docs/api/api-spec.md O-25. "reupload"(원본 다시 올리기)는 #357 부터 받지 않고,
// 본문을 바로 고치는 ReviseBlockedEntryRequest 로 바뀌었다.
public record ValidationResolveRequest(String action) {
}
