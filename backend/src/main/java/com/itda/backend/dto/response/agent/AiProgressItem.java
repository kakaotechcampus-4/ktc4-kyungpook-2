package com.itda.backend.dto.response.agent;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AI {@code GET /progress} 한 줄 (#452). 에이전트마다 채워지는 키가 다르다.
 *
 * @param agent          {@code matching} · {@code validation} · {@code summary} · {@code insight}
 * @param journalEntryId 매칭·검증의 일지 id
 * @param childId        요약·리포트의 아이 id
 * @param entryDate      요약의 일지 날짜
 * @param institutionId  요약의 기관 id
 * @param periodFrom     리포트 기간의 시작(월요일). 끝({@code period_to})은 진행 현황 주 줄이 시작일로 알아보므로 읽지 않는다
 * @param node           지금 들어가 있는 노드
 * @param elapsedSec     일을 시작한 뒤 지난 초. 재시도도 이어서 센다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiProgressItem(
        String agent,
        @JsonProperty("journal_entry_id") Long journalEntryId,
        @JsonProperty("child_id") Long childId,
        @JsonProperty("entry_date") LocalDate entryDate,
        @JsonProperty("institution_id") Long institutionId,
        @JsonProperty("period_from") LocalDate periodFrom,
        String node,
        @JsonProperty("elapsed_sec") int elapsedSec) {
}
