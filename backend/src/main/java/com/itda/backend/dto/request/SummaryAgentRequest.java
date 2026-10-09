package com.itda.backend.dto.request;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 요약 에이전트 {@code POST /summary} 요청. 필드는 {@code AI/summary/schemas.py} 의 SummaryInput 과 1:1 이다.
 * ⚠️ {@code otherChildNames} 만 예외로 AI 쪽에 아직 없다 — AI 가 모르는 필드는 버리므로 요청은 깨지지 않고,
 * AI 가 반영하면 그때부터 쓰인다.
 *
 * <p>일지 한 건이 아니라 아동 × 날짜 × 기관 묶음의 일지 N건을 한 번에 보낸다.
 *
 * @param entryDate       묶음 기준 날짜 (ISO, 예: "2026-10-08")
 * @param otherChildNames 본문에 이름이 나온 주인공 외 아이들. AI 는 프롬프트에 넣지 않고 요약 본문에 이름이
 *                        남았는지 재는 데만 쓴다. 매칭이 명부에서 이름 전체로 찾은 아이만 담기므로 빠짐없는 목록은 아니다
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SummaryAgentRequest(
        Long childId,
        String childName,
        String entryDate,
        Long institutionId,
        String institutionName,
        List<Source> sources,
        List<String> otherChildNames) {

    /** 요약 재료가 되는 일지 한 건 (SourceEntry). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Source(Long journalEntryId, String content, String entryDate) {
    }
}
