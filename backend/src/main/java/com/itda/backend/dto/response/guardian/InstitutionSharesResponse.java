package com.itda.backend.dto.response.guardian;

import java.time.LocalDateTime;
import java.util.List;

import com.itda.backend.dto.response.organization.InstitutionResponse;

/**
 * 보호자의 기관 공유 내역 중 한 기관 (G-45). 그 기관에 열린 이 아이의 주간 리포트와 지금 상태.
 *
 * @param institution 리포트를 받은 기관
 * @param shares      열린 순서 최신부터
 */
public record InstitutionSharesResponse(
        InstitutionResponse institution,
        List<SharedInsight> shares) {

    /**
     * 받은 리포트 한 편.
     *
     * @param period    리포트 탭과 같은 "2026년 10월 4주차" 같은 문구
     * @param from      이 리포트를 Gate 2 에서 승인한 기관. 없어진 기관이면 {@code null}
     * @param grantedAt 이 기관에 열린 시각
     * @param visible   그 기관이 지금 볼 수 있는지. 기관 수신함과 같은 판정이다
     * @param hiddenAt  가려진 시각(권한을 거둔 시각). 볼 수 있거나, 거둔 기록 없이 명부 · 동의로 가려졌으면 {@code null}
     */
    public record SharedInsight(
            String insightId,
            String period,
            InstitutionResponse from,
            LocalDateTime grantedAt,
            boolean visible,
            LocalDateTime hiddenAt) {
    }
}
