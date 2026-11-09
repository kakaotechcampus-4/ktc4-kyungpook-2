package com.itda.backend.dto.response.agent;

import java.time.OffsetDateTime;

public record AiStatusResponse(boolean available, OffsetDateTime since) {
}
