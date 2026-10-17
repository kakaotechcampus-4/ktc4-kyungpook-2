package com.itda.backend.fixture;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 테스트가 옮길 수 있는 시계. 초대 코드 만료처럼 "잠금을 기다리는 동안 시간이 흐른" 상황을 재현하려고 쓴다.
 * 여러 스레드가 함께 읽으므로 현재 시각을 volatile 로 둔다.
 */
public final class MutableClock extends Clock {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ZoneId zone;
    private volatile Instant now;

    public MutableClock(LocalDateTime now) {
        this(now.atZone(KST).toInstant(), KST);
    }

    private MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void setTo(LocalDateTime time) {
        this.now = time.atZone(zone).toInstant();
    }

    public void advance(Duration duration) {
        this.now = this.now.plus(duration);
    }

    public LocalDateTime localNow() {
        return LocalDateTime.ofInstant(now, zone);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
