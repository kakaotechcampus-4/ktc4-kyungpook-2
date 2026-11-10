package com.itda.backend.dto.response.dashboard;

import java.time.LocalDate;
import java.util.List;

import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Kind;

public record DashboardAiProgressResponse(boolean enabled, List<Item> items) {

    public static DashboardAiProgressResponse disabled() {
        return new DashboardAiProgressResponse(false, List.of());
    }

    public record Item(
            Kind kind,
            String childId,
            LocalDate date,
            LocalDate weekFrom,
            String agent,
            String node,
            int elapsedSec) {
    }
}
