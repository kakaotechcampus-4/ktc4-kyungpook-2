package com.itda.backend.service.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

@Component
public class AiAvailability {

    static final Duration DOWN_AFTER = Duration.ofSeconds(15);
    static final int MIN_FAILURES = 3;
    static final Duration GAVE_UP_HOLD = Duration.ofSeconds(60);

    private final Clock clock;

    private Instant failingSince;
    private int failures;

    private Instant gaveUpSince;
    private Instant lastGaveUp;

    public AiAvailability(Clock clock) {
        this.clock = clock;
    }

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

    public synchronized void reportCallGaveUp() {
        Instant now = clock.instant();
        expireGaveUp(now);
        if (gaveUpSince == null) {
            gaveUpSince = now;
        }
        lastGaveUp = now;
    }

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

    public record Status(boolean available, Instant since) {
    }
}
