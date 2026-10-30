package com.itda.backend.dto.response.child;

/**
 * 보호자 홈의 "최근 공유 활동" 한 줄 (api-spec G-11). 지금은 승인된 일지 하나가 한 줄이다.
 *
 * @param at        화면에 그대로 보여 줄 시각 문구 {@code yyyy-MM-dd HH:mm}. 일지면 승인 시각
 * @param text      예: "○○초등학교에서 8월 21일 일지가 도착했어요"
 * @param journalId 있으면 화면이 일지 상세로 연결한다. 없으면 null
 */
public record ParentActivityResponse(String id, String at, String text, String journalId) {
}
