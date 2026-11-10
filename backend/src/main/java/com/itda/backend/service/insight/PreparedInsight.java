package com.itda.backend.service.insight;

import com.itda.backend.dto.request.insight.InsightAgentRequest;

public record PreparedInsight(InsightAgentRequest request, Long lastContextId) {
}
