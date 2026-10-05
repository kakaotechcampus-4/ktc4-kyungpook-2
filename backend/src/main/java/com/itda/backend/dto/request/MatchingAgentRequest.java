package com.itda.backend.dto.request;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 매칭 에이전트 {@code POST /matching} 요청. 필드는 {@code AI/matching/schemas.py} 의 MatchingInput 과 1:1 이다.
 *
 * <p>날짜는 문자열로 보낸다 — AI 는 생년월일을 문자열 그대로 비교한다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MatchingAgentRequest(
        Long journalEntryId,
        String content,
        List<RosterEntry> roster,
        Long rawRecordId,
        String entryDate,
        String hintName,
        String hintBirthdate) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RosterEntry(Long childId, String name, String birthdate) {
    }
}
