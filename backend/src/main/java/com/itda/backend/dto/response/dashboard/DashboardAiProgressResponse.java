package com.itda.backend.dto.response.dashboard;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Kind;

/**
 * 대시보드 진행 현황에 띄울 AI 진행 단계 (#453, api-spec O-83). FE 는 {@code kind} · {@code childId} ·
 * {@code date}/{@code weekFrom} 으로 진행 현황 줄(O-80)에 이어 붙이고, {@code node} 를 문구로 바꾼다.
 *
 * @param enabled 서버에서 켜 두었는지. 꺼져 있으면 {@code items} 는 늘 비어 있다
 * @param items   지금 AI 가 처리 중인 줄. 붙일 줄이 없거나 실패하면 비어 있고, 화면은 기존 고정 문구를 띄운다
 */
public record DashboardAiProgressResponse(boolean enabled, List<Item> items) {

    public static DashboardAiProgressResponse disabled() {
        return new DashboardAiProgressResponse(false, List.of());
    }

    /**
     * 진행 현황 한 줄의 AI 진행 단계.
     *
     * @param kind       {@code entry}(아이×날짜) · {@code week}(아이×주) — 진행 현황 줄과 같은 값
     * @param childId    아이 id. 매칭 줄은 아직 아이가 없어 {@code null}
     * @param date       {@code entry} 줄의 일지 날짜
     * @param weekFrom   {@code week} 줄의 주 시작일(월요일)
     * @param agent      {@code matching} · {@code validation} · {@code summary} · {@code insight}
     * @param node       AI 가 지금 들어가 있는 단계 (#452)
     * @param elapsedSec 그 일을 시작한 뒤 지난 초
     */
    public record Item(
            Kind kind,
            String childId,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate date,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate weekFrom,
            String agent,
            String node,
            int elapsedSec) {
    }
}
