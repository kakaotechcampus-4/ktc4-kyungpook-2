package com.itda.backend.dto.response.guardian;

import java.time.LocalDateTime;
import java.util.List;

import com.itda.backend.dto.response.organization.InstitutionResponse;

/**
 * 보호자가 열람 권한을 받은 인사이트 한 편 (#233).
 *
 * @param period     화면에 그대로 쓰는 "2026년 8월 3주차" 같은 문구
 * @param from       이 글을 Gate 2 에서 승인한 기관
 * @param sharedWith 지금 이 글을 볼 수 있는 다른 기관. 승인할 때 동의돼 있던 기관이고, 동의를 끄면 빠진다
 * @param approvedAt 담당자가 Gate 2 에서 승인한 시각. 모든 보호자에게 같다 — 늦게 연결돼 나중에 열린 글도 이 시각이다
 * @param readAt     보호자가 처음 열어 본 시각. 아직 안 봤으면 {@code null}
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
        LocalDateTime readAt) {
}
