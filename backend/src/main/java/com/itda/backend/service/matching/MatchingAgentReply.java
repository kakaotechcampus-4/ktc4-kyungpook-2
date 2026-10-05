package com.itda.backend.service.matching;

import com.itda.backend.dto.response.MatchingAgentResponse;

/** 파싱한 응답과 응답 원문. 원문은 matching_result.raw_response 에 그대로 남긴다. */
public record MatchingAgentReply(MatchingAgentResponse response, String rawJson) {
}
