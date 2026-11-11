package com.itda.backend.service.summary;

import com.itda.backend.dto.response.summary.SummaryAgentResponse;

public record SummaryAgentReply(SummaryAgentResponse response, String rawJson) {
}
