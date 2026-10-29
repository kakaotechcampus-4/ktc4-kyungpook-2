package com.itda.backend.dto.response.child;

import java.time.OffsetDateTime;

import com.itda.backend.dto.response.organization.InstitutionResponse;

/**
 * 보호자가 낸 기관 연결 요청. 지금은 언제나 기관의 답을 기다리는 {@code pending} 이다 — 수락되면 아이의 기관 목록으로
 * 옮겨 가고, 거절되면 목록에서 사라진다.
 *
 * @param requestedAt 요청한 시각
 */
public record InstitutionLinkRequestResponse(InstitutionResponse institution, String status, OffsetDateTime requestedAt) {

    public static final String PENDING = "pending";
}
