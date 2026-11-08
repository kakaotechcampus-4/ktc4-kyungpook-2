package com.itda.backend.dto.response.notify;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code AI POST /notify} 응답 (#419).
 *
 * <p>200 으로 오는 길이 셋이다 (AI/notify/CRITERIA.md §4).
 * <ul>
 *   <li>{@code sent} — 나갔다</li>
 *   <li>{@code blocked} — 근거 검사에 걸려 <b>보내지 않았다</b>. 교사가 봐야 한다</li>
 *   <li>{@code nothing_to_send} — 승인된 인사이트가 없었다</li>
 * </ul>
 * 모델 고장과 메일 서버 장애는 503 이라 여기 오지 않는다 — 예외로 올라간다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotifyAgentResponse(
        @JsonProperty("child_id") Long childId,
        @JsonProperty("notification_id") String notificationId,
        String status,
        @JsonProperty("message_id") String messageId,
        boolean duplicate,
        @JsonProperty("blocked_lines") List<BlockedLine> blockedLines,
        @JsonProperty("llm_called") boolean llmCalled) {

    public static final String SENT = "sent";
    public static final String NO_RECIPIENT = "no_recipient";
    public static final String BLOCKED = "blocked";
    public static final String NOTHING_TO_SEND = "nothing_to_send";

    /**
     * 검사에 걸린 줄 하나.
     *
     * <p>⚠️ {@code text} 는 아이 기록이다. <b>DB 에 옮겨 적지 않는다</b> — 사유({@code reasons})만 남기고
     * 문장은 로그로만 간다 ({@code FailureReason} 과 같은 기준).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlockedLine(String text, List<String> reasons) {
    }

    public boolean isSent() {
        return SENT.equals(status);
    }

    public boolean isBlocked() {
        return BLOCKED.equals(status);
    }

    /** 글은 통과했는데 받을 보호자가 없었다. {@link #isSent()} 와 나눈다 (#430 리뷰). */
    public boolean isNoRecipient() {
        return NO_RECIPIENT.equals(status);
    }
}
