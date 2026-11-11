package com.itda.backend.service.validation;

import com.itda.backend.dto.request.validation.ValidationAgentRequest;

public record ValidationTarget(ValidationAgentRequest request, Long matchingResultId) {
}
