package com.itda.backend.service.summary;

import com.itda.backend.dto.response.SummaryAgentResponse;

/** 파싱한 응답과 응답 원문. 원문은 summary_result.raw_response 에 그대로 남긴다. */
public record SummaryAgentReply(SummaryAgentResponse response, String rawJson) {
}
