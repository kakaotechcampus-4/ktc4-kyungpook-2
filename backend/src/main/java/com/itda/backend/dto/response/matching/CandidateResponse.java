package com.itda.backend.dto.response.matching;

public record CandidateResponse(String childId, String name, String birthDate, Double confidence) {
}
