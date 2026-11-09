package com.itda.backend.dto.response.guardian;

import java.time.LocalDate;
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
 * @param kind       {@code "insight"} 는 리포트, {@code "quiet"} 는 리포트가 없는 지난 주를 "특이사항 없음" 으로 보이는
 *                   항목이다 (#470). quiet 은 아이 · 주만 담는다 — 본문 · 기관 · 근거가 없고 열어 볼 상세도 없다.
 *                   {@code approvedAt} 은 승인 시각 대신 그 주가 끝난 다음 날(월요일) 0시라 목록 정렬에만 쓴다
 * @param quietReason quiet 일 때만. {@code NOTHING_NOTABLE}(① 기록이 모두 "특이사항 없음" 확인) ·
 *                   {@code NOTHING_NEW}(② 기록은 있었지만 분석이 낼 것이 없었음)
 */
public record GuardianInsightResponse(
        String id,
        String childId,
        String childName,
        String period,
        /**
         * 이 글이 다룬 주의 시작·끝 {@code yyyy-MM-dd}. 화면이 <b>달로 묶을 때</b> 쓴다.
         *
         * <p>{@code period} 글자("2026년 8월 3주차")를 잘라 달을 구하면 깨진다 — 9/29~10/5 처럼
         * 달에 걸친 주가 있고, 문구가 바뀌면 같이 깨진다. 달은 {@code periodFrom} 기준이다
         * ({@code periodLabel} 과 같은 기준).
         */
        String periodFrom,
        String periodTo,
        String content,
        InstitutionResponse from,
        List<InstitutionResponse> sharedWith,
        LocalDateTime approvedAt,
        LocalDateTime readAt,
        List<Evidence> evidence,
        String kind,
        String quietReason) {

    public static final String INSIGHT = "insight";
    public static final String QUIET = "quiet";

    /** 리포트가 없는 지난 주 (#470). id 는 목록 key 로만 쓴다 — 단건 경로로 열 수 없다. */
    public static GuardianInsightResponse quiet(String childId, String childName, String period, LocalDate periodFrom,
            LocalDate periodTo, String quietReason) {
        return new GuardianInsightResponse("quiet-" + childId + "-" + periodFrom, childId, childName, period,
                periodFrom.toString(), periodTo.toString(), null, null, List.of(),
                periodTo.plusDays(1).atStartOfDay(), null, List.of(), QUIET, quietReason);
    }

    /**
     * 근거 일지 한 건.
     *
     * @param journalEntryId 보호자 일지 id(G-21) — 승인본({@code child_context}) id 다
     * @param date           관찰 날짜 {@code yyyy-MM-dd}
     * @param institution    그 일지를 쓴 기관. 명부에서 빠진 기관일 수 있어 이름까지 준다
     * @param lookedUp       이번 기간이 아니라 AI 가 지난 기록에서 찾아 쓴 근거인지 (Gate 2 근거와 같은 값, #387)
 * @param sentences      그 일지에서 이 글이 실제로 인용한 문장. 못 찾으면 빈 배열이다
     */
    public record Evidence(String journalEntryId, String date, InstitutionResponse institution,
            boolean lookedUp, List<Sentence> sentences) {
    }

    /**
     * 그 일지에서 이 글이 실제로 인용한 문장. 없으면 빈 배열이고, 근거 줄은 그대로 보인다.
     *
     * <p><b>Gate 1 에서 교사가 승인한 요약 문장이다.</b> 원본 일지 인용이 아니다 — 원본에는 다른 아이
     * 이름이 남아 있어 밖으로 내보내지 않는다 ({@code AI/insight/CRITERIA.md §2}).
     *
     * @param role {@code "상황"} 또는 {@code "대처"}
     */
    public record Sentence(String text, String role) {
    }
}
