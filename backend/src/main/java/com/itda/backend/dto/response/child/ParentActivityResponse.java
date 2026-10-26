package com.itda.backend.dto.response.child;

/**
 * 보호자 홈의 "최근 공유 활동" 한 줄 (api-spec G-11). 아직 만드는 곳이 없다 — 일지 기능과 함께 채운다.
 * 응답 모양을 OpenAPI 에 남기려고 미리 둔다.
 *
 * @param at        화면에 그대로 보여 줄 시각 문구
 * @param text      예: "○○초등학교에 관찰 요약이 전달되었습니다"
 * @param journalId 있으면 화면이 일지 상세로 연결한다. 없으면 null
 */
public record ParentActivityResponse(String id, String at, String text, String journalId) {
}
