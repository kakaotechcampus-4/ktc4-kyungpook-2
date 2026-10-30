package com.itda.backend.dto.response.sharing;

import java.time.LocalDateTime;

import com.itda.backend.dto.response.organization.InstitutionResponse;

/**
 * 기관 수신함 단건 (#273). 목록 한 줄({@link InboxItemResponse})에 본문을 더한 모양이다.
 *
 * @param content 인사이트 본문. 보호자에게 열린 것과 같은 글이다 — 수신자별 변환은 아직 없다
 * @param readAt  처음 열어 본 시각. 이 단건을 열면서 찍히므로 비어 있지 않다
 */
public record InboxDetailResponse(
        String id,
        String childId,
        String childName,
        String externalId,
        InstitutionResponse from,
        String period,
        LocalDateTime receivedAt,
        LocalDateTime readAt,
        String content) {
}
