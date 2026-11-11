package com.itda.backend.service.notify;

import com.itda.backend.dto.request.notify.NotifyAgentRequest;

public record PreparedNotify(NotifyAgentRequest request, int insightCount, Long lastInsightId) {
}
