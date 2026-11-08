package com.itda.backend.dto.response.guardian;

import java.time.LocalDateTime;
import java.util.List;

import com.itda.backend.dto.response.organization.InstitutionResponse;

/**
 * 보호자가 열람 권한을 받은 인사이트 한 편 (#233).
 *
 * @param period     화면에 그대로 쓰는 "2026년 8월 3주차" 같은 문구
 * @param from       이 글을 Gate 2 에서 승인한 기관
 * @param sharedWith 지금 이 글을 볼 수 있는 다른 기관, 이름순. 기관 수신함 · 공유 내역(G-45)과 같은 판정이다 —
 *                   권한이 살아 있고, 아이가 그 기관 명부에 있고, 그 기관 동의가 켜져 있어야 한다 (#319)
 * @param approvedAt 담당자가 Gate 2 에서 승인한 시각. 모든 보호자에게 같다 — 늦게 연결돼 나중에 열린 글도 이 시각이다
 * @param readAt     보호자가 처음 열어 본 시각. 아직 안 봤으면 {@code null}
 * @param evidence   이 글의 근거가 된 일지, 관찰 날짜 오래된 순 (#405). Gate 2 근거와 같은 범위에서 <b>보호자가 지금 일지
 *                   상세(G-21)로 열 수 있는 것</b>만 — 동의를 끈 기관 · 명부에서 빠진 기관의 일지도 남는다(일지 탭과 같다).
 *                   남는 것이 없으면 빈 배열이고, 글은 그대로 보인다
 */
public record GuardianInsightResponse(
        String id,
        String childId,
        String childName,
        String period,
        String content,
        InstitutionResponse from,
        List<InstitutionResponse> sharedWith,
        LocalDateTime approvedAt,
        LocalDateTime readAt,
        List<Evidence> evidence) {

    /**
     * 근거 일지 한 건.
     *
     * @param journalEntryId 보호자 일지 id(G-21) — 승인본({@code child_context}) id 다
     * @param date           관찰 날짜 {@code yyyy-MM-dd}
     * @param institution    그 일지를 쓴 기관. 명부에서 빠진 기관일 수 있어 이름까지 준다
     */
    public record Evidence(String journalEntryId, String date, InstitutionResponse institution) {
    }
}
