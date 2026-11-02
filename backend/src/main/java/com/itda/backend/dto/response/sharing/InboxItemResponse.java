package com.itda.backend.dto.response.sharing;

import java.time.LocalDateTime;

import com.itda.backend.dto.response.organization.InstitutionResponse;

/**
 * 기관 수신함 목록의 한 줄 (#273). <b>본문이 없다</b> — 본문은 단건에서만 주고, 단건을 열 때 처음 열어 본 시각을
 * 남긴다. 목록이 본문을 주면 열지 않고도 다 읽어서 열람 시각이 의미를 잃는다.
 *
 * <p>같이 받은 다른 기관은 넣지 않는다. 보호자 화면과 달리, 기관에게는 "이 아이가 또 어디에 다니는지" 가
 * 필요한 최소 정보를 넘는다.
 *
 * @param id         인사이트 id. 단건 경로에 그대로 쓴다
 * @param externalId 우리 기관이 붙인 관리번호. 이름이 같은 아이를 가른다
 * @param from       이 글을 Gate 2 에서 승인한 기관
 * @param period     "2026년 8월 3주차" 같은 문구 (보호자 인사이트와 같은 규칙)
 * @param receivedAt 우리 기관이 볼 수 있게 된 시각
 * @param readAt     우리 기관에서 처음 열어 본 시각. 아직 안 봤으면 {@code null} 로 나간다
 */
public record InboxItemResponse(
        String id,
        String childId,
        String childName,
        String externalId,
        InstitutionResponse from,
        String period,
        LocalDateTime receivedAt,
        LocalDateTime readAt) {
}
