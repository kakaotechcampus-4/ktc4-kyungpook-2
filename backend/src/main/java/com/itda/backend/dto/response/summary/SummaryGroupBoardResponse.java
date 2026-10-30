package com.itda.backend.dto.response.summary;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 오늘의 업무 — 요약 전 묶음(아동 × 날짜) 카드 목록 (O-34). 요약이 만들어진 묶음은 1차 검토(O-30)로 옮겨 가 여기에
 * 없다.
 *
 * @param matching 아이가 아직 정해지지 않아 카드에 넣을 수 없는 일지 건수
 * @param groups   요약 전 묶음 카드. 오래된 날짜부터
 */
public record SummaryGroupBoardResponse(MatchingCounts matching, List<SummaryGroupCard> groups) {

    /**
     * @param inProgress  자동 매칭 중(등록·매칭 중)
     * @param needsReview 확인 필요 큐에서 교사가 아이를 골라야 하는 일지
     */
    public record MatchingCounts(long inProgress, long needsReview) {
    }

    /**
     * @param status                  할 일 우선 라벨: {@code needs_fix} · {@code ready} · {@code summarizing} ·
     *                                {@code validating} · {@code admin_check}
     * @param canSummarize            [요약]을 누를 수 있는지. 요약 대기 일지가 있고, 누르지 않았고 요약 중도 아닐 때
     * @param summaryRequested        [요약]을 눌렀고 아직 요약이 끝나지 않았다
     * @param waitingCount            눌렀지만 아직 시작 전일 때, 요약이 끝나길 기다리는 같은 날 매칭·검증 중 일지 수
     * @param previousSummaryInReview 이 묶음의 이전 요약이 1차 검토에 있다. 다시 요약하면 새 일지까지 합친 새 판이 된다
     */
    public record SummaryGroupCard(
            Long childId,
            String childName,
            LocalDate birthdate,
            LocalDate entryDate,
            @Schema(allowableValues = {"needs_fix", "ready", "summarizing", "validating", "admin_check"})
            String status,
            Counts counts,
            boolean canSummarize,
            boolean summaryRequested,
            long waitingCount,
            boolean previousSummaryInReview) {
    }

    /**
     * @param validating  검증 대기·검증 중
     * @param needsFix    검증에서 막혀 수정 요청 큐에 있음
     * @param ready       검증을 통과해 요약 대기
     * @param summarizing 요약 중
     * @param adminCheck  시스템 오류로 관리자 확인 중
     */
    public record Counts(long validating, long needsFix, long ready, long summarizing, long adminCheck) {
    }
}
