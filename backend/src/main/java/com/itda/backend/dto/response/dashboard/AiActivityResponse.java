package com.itda.backend.dto.response.dashboard;

public record AiActivityResponse(long split, long matched, long validated, long summarized, long insights) {
}
