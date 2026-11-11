package com.itda.backend.service.matching;

import com.itda.backend.dto.response.matching.MatchingAgentResponse;

public record MatchingAgentReply(MatchingAgentResponse response, String rawJson) {
}
