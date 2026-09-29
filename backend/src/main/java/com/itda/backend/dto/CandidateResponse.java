package com.itda.backend.dto;

// frontend/app/lib/types.ts의 MatchingItem.candidates[] 항목과 대응. group(반)은
// Child에 그 필드가 없어서 못 채운다.
public record CandidateResponse(String childId, String name, String birthDate, Double confidence) {
}
