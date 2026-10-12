package com.itda.backend.dto;

// action: "reupload"(원본을 고쳐 다시 올림) 또는 "hold"(보류) — docs/api/api-spec.md O-25
public record ValidationResolveRequest(String action) {
}
