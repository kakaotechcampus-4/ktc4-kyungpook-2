package com.itda.backend.service.validation;

import com.itda.backend.dto.response.validation.ValidationAgentResponse;

public record ValidationAgentReply(ValidationAgentResponse response, String rawJson) {
}
