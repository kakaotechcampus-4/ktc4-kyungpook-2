package com.itda.backend.dto.response.notify;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlockedLine(String text, List<String> reasons) {
    }

    public boolean isSent() {
        return SENT.equals(status);
    }

    public boolean isBlocked() {
        return BLOCKED.equals(status);
    }

    public boolean isNoRecipient() {
        return NO_RECIPIENT.equals(status);
    }
}
