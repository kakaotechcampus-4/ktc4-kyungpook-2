package com.itda.backend.dto.response.guardian;

import java.time.LocalDateTime;
import java.util.List;

import com.itda.backend.dto.response.organization.InstitutionResponse;

/**
 * 보호자가 열람 권한을 받은 인사이트 한 편 (#223).
 *
 * <p>Gate 2 를 지난 글이라 담당자가 이미 한 번 봤다. 보호자는 여기서 <b>어느 기관에 열어줄지</b>를
 * 고른다 — 10/17 회의 결론 {@code 담당자 승인 → 보호자 → 보호자가 기관별 승인} 의 마지막 칸이다.
 *
 * @param period     화면에 그대로 쓰는 "2026년 8월 3주차" 같은 문구
 * @param from       이 글을 승인해 보낸 기관
 * @param sharedWith 고를 수 있는 기관과 지금 열려 있는지. <b>이미 끈 기관도 함께 온다</b> —
 *                   다시 켤 수 있어야 하므로 목록에서 사라지면 안 된다
 * @param readAt     보호자가 처음 열어 본 시각. 아직 안 봤으면 {@code null}
 */
public record GuardianInsightResponse(
        String id,
        String childId,
        String childName,
        String period,
        String content,
        InstitutionResponse from,
        List<ShareTarget> sharedWith,
        LocalDateTime readAt) {

    /**
     * 공유 후보 기관 하나.
     *
     * @param shared 지금 이 기관이 이 글을 볼 수 있는지
     */
    public record ShareTarget(InstitutionResponse institution, boolean shared) {
    }
}
