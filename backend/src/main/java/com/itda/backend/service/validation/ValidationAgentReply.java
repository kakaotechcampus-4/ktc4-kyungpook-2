package com.itda.backend.service.validation;

import com.itda.backend.dto.response.ValidationAgentResponse;

/** 파싱한 응답과 응답 원문. 원문은 validation_result.raw_response 에 그대로 남긴다. */
public record ValidationAgentReply(ValidationAgentResponse response, String rawJson) {
}
