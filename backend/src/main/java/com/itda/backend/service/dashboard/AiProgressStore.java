package com.itda.backend.service.dashboard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.itda.backend.dto.response.dashboard.DashboardAiProgressResponse.Item;
import com.itda.backend.service.agent.AiProgressProperties;

/**
 * 폴러가 번역한 AI 진행 단계를 기관별로 들고 있는 메모리 보관함 (#453). BE 서버가 한 대라 메모리로 충분하다.
 *
 * <p>폴러가 쓰고 진행 API 가 읽는다. 통째로 바꿔 끼우는 불변 스냅샷이라 따로 잠그지 않는다.
 * 마지막으로 받은 지 3초(간격·대기를 늘리면 {@code 2 × (간격 + 대기)})가 넘으면 비어 있는 것으로 읽는다 — AI 조회나
 * 번역이 계속 실패하거나 폴러가 쉬다가 깨어났을 때 옛 문구가 남지 않게.
 */
@Component
public class AiProgressStore {

    /** 이보다 오래된 스냅샷은 읽지 않는다. 간격 1초 기준으로 두세 번 연달아 실패한 것이다. */
    static final Duration MIN_STALE = Duration.ofSeconds(3);

    /** 이 안에 진행 API 를 부른 화면이 없으면 폴러가 AI 에 묻지 않는다. */
    static final Duration ASKED_WITHIN = Duration.ofSeconds(5);

    private final Clock clock;
    /**
     * 한 차례는 간격만큼 쉬고 최대 대기 시간만큼 묻는다. 두 차례 동안은 버텨야 실패 한 번에 화면이 비지 않는다.
     * 기본값(1초 · 0.5초)이면 3초다.
     */
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

    /** 진행 API 가 불릴 때마다 적는다. */
    public void markAsked() {
        lastAskedAt = clock.instant();
    }

    public boolean askedRecently() {
        return !lastAskedAt.plus(ASKED_WITHIN).isBefore(clock.instant());
    }

    private record Snapshot(Map<Long, List<Item>> byOrganization, Instant receivedAt) {
    }
}
