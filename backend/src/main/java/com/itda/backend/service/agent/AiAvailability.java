package com.itda.backend.service.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 워커들이 본 AI 상태를 한 곳에 모아 "AI 가 멈췄는지"를 정한다. 기관 화면의 AI 장애 배너가 읽는다 (#370).
 *
 * <p>멈춘 것으로 보는 경우는 둘이다.
 * <ul>
 *   <li>{@code /health} 실패가 마지막 성공 뒤로 {@link #DOWN_AFTER} 넘게, {@link #MIN_FAILURES} 번 넘게 이어질 때.
 *       워커 네 개가 5초마다 같은 순간에 보고하므로 횟수만 세면 한 번 깜빡여도 멈춘 것이 된다 — 시간으로 묶는다.</li>
 *   <li>요약 워커가 재시도 끝에 AI 를 못 불렀을 때. {@code /health} 는 LLM 까지 보지 않아 LLM 장애 동안에도 성공하므로,
 *       {@link #GAVE_UP_HOLD} 동안은 {@code /health} 성공으로 풀지 않는다. 요약 호출이 성공하면 바로 풀린다.</li>
 * </ul>
 * {@code since} 는 지금 이어지는 장애가 처음 보인 시각이다. 앱이 다시 켜지면 처음부터 다시 센다(메모리에만 둔다).
 */
@Component
public class AiAvailability {

    static final Duration DOWN_AFTER = Duration.ofSeconds(15);
    static final int MIN_FAILURES = 3;
    static final Duration GAVE_UP_HOLD = Duration.ofSeconds(60);

    private final Clock clock;

    /** 마지막 성공 뒤 첫 {@code /health} 실패 시각. 성공하면 비운다. */
    private Instant failingSince;
    private int failures;

    /** 요약 워커가 처음 멈춘 시각과 마지막으로 멈춘 시각. 호출이 성공하거나 마지막 멈춤에서 GAVE_UP_HOLD 가 지나면 비운다. */
    private Instant gaveUpSince;
    private Instant lastGaveUp;

    public AiAvailability(Clock clock) {
        this.clock = clock;
    }

    /** 워커가 일을 집기 전에 본 {@code /health} 결과. */
    public synchronized void reportHealth(boolean available) {
        if (available) {
            failingSince = null;
            failures = 0;
            return;
        }
        if (failingSince == null) {
            failingSince = clock.instant();
        }
        failures++;
    }

    /** 요약 워커가 재시도까지 응답을 못 받아 멈췄다. */
    public synchronized void reportCallGaveUp() {
        Instant now = clock.instant();
        expireGaveUp(now);
        if (gaveUpSince == null) {
            gaveUpSince = now;
        }
        lastGaveUp = now;
    }

    /** 요약 호출이 응답을 받았다. LLM 이 살아 있다는 뜻이라 멈춤을 바로 푼다. */
    public synchronized void reportCallSucceeded() {
        gaveUpSince = null;
        lastGaveUp = null;
    }

    public synchronized Status status() {
        Instant now = clock.instant();
        expireGaveUp(now);
        Instant since = gaveUpSince;
        boolean healthDown = failingSince != null && failures >= MIN_FAILURES
                && !now.isBefore(failingSince.plus(DOWN_AFTER));
        if (healthDown && (since == null || failingSince.isBefore(since))) {
            since = failingSince;
        }
        return new Status(since == null, since);
    }

    private void expireGaveUp(Instant now) {
        if (lastGaveUp != null && !now.isBefore(lastGaveUp.plus(GAVE_UP_HOLD))) {
            gaveUpSince = null;
            lastGaveUp = null;
        }
    }

    /** @param since 멈춘 것으로 볼 때만 값이 있다 */
    public record Status(boolean available, Instant since) {
    }
}
