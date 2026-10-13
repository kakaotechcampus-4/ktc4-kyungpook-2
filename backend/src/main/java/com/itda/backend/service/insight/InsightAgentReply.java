package com.itda.backend.service.insight;

import com.itda.backend.dto.response.insight.InsightAgentResponse;

/** 파싱한 응답과 응답 원문. 원문은 insight_result.raw_response 에 그대로 남긴다. */
public record InsightAgentReply(InsightAgentResponse response, String rawJson) {
}
