package com.itda.backend.service.dashboard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.itda.backend.dto.response.dashboard.DashboardAiProgressResponse.Item;
import com.itda.backend.service.agent.AiProgressProperties;

@Component
public class AiProgressStore {

    static final Duration MIN_STALE = Duration.ofSeconds(3);

    static final Duration ASKED_WITHIN = Duration.ofSeconds(5);

    private final Clock clock;
    private final Duration stale;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Instant.MIN);
    private volatile Instant lastAskedAt = Instant.MIN;

    public AiProgressStore(Clock clock, AiProgressProperties properties) {
        this.clock = clock;
        Duration twoTicks = properties.interval().plus(properties.timeout()).multipliedBy(2);
        this.stale = twoTicks.compareTo(MIN_STALE) > 0 ? twoTicks : MIN_STALE;
    }

    public List<Item> read(Long organizationId) {
        Snapshot current = snapshot;
        if (current.receivedAt().plus(stale).isBefore(clock.instant())) {
            return List.of();
        }
        return current.byOrganization().getOrDefault(organizationId, List.of());
    }

    public void replace(Map<Long, List<Item>> byOrganization) {
        snapshot = new Snapshot(Map.copyOf(byOrganization), clock.instant());
    }

    public void markAsked() {
        lastAskedAt = clock.instant();
    }

    public boolean askedRecently() {
        return !lastAskedAt.plus(ASKED_WITHIN).isBefore(clock.instant());
    }

    private record Snapshot(Map<Long, List<Item>> byOrganization, Instant receivedAt) {
    }
}
