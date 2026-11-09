package com.itda.backend.service.insight;

import com.itda.backend.dto.response.insight.InsightAgentResponse;

public record InsightAgentReply(InsightAgentResponse response, String rawJson) {
}
