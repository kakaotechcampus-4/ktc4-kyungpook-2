package com.itda.backend.dto.response.child;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 보호자 홈의 "최근 공유 활동" 한 줄 (api-spec G-11). 승인된 일지 하나, 또는 같은 기간·같은 승인 기관의 받은 인사이트
 * 묶음 하나가 한 줄이다 (#289).
 *
 * @param id        목록 안에서 겹치지 않는 값. {@code journal-3} · {@code insight-2026-08-17-2} 처럼 종류를 앞에 붙인다
 * @param kind      {@code journal} 또는 {@code insight}
 * @param at        화면에 그대로 보여 줄 시각 문구 {@code yyyy-MM-dd HH:mm}. 일지는 Gate 1 승인, 인사이트는 Gate 2 승인 시각
 * @param text      예: "○○초등학교에서 8월 21일 일지가 도착했어요"
 * @param journalId 일지 줄이면 일지 상세로 연결할 id. 아니면 null
 * @param insightId 인사이트 한 편이면 그 id. 여러 편을 묶은 줄이거나 일지 줄이면 null — 화면은 리포트 탭으로 연결한다
 */
public record ParentActivityResponse(
        String id,
        @Schema(allowableValues = {"journal", "insight"}) String kind,
        String at,
        String text,
        String journalId,
        String insightId) {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static ParentActivityResponse journal(String journalId, LocalDateTime at, String text) {
        return new ParentActivityResponse("journal-" + journalId, "journal", at.format(AT), text, journalId, null);
    }

    /** @param key 묶음을 가르는 값. 같은 기간·같은 기관이면 같다 */
    public static ParentActivityResponse insight(String key, LocalDateTime at, String text, String insightId) {
        return new ParentActivityResponse("insight-" + key, "insight", at.format(AT), text, null, insightId);
    }
}
