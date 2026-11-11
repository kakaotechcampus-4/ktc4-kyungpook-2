package com.itda.backend.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.itda.backend.fixture.MutableClock;

class AiAvailabilityTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 10, 0);

    private MutableClock clock;
    private AiAvailability availability;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        availability = new AiAvailability(clock);
    }

    @Test
    void 아무_보고도_없으면_사용_가능하다() {
        assertThat(availability.status()).isEqualTo(new AiAvailability.Status(true, null));
    }

    @Test
    void 상태_확인_실패가_15초_넘게_이어지면_첫_실패_시각부터_멈춘_것으로_본다() {
        failEvery5Seconds(3);
        assertThat(availability.status().available()).isTrue();

        failEvery5Seconds(1);

        assertThat(availability.status())
                .isEqualTo(new AiAvailability.Status(false, clock.instant().minusSeconds(15)));
    }

    @Test
    void 여러_워커가_한꺼번에_실패를_보고해도_15초가_안_되면_멈춘_것으로_보지_않는다() {
        for (int i = 0; i < 8; i++) {
            availability.reportHealth(false);
        }
        clock.advance(Duration.ofSeconds(14));
        availability.reportHealth(false);

        assertThat(availability.status().available()).isTrue();
    }

    @Test
    void 상태_확인이_한_번_성공하면_다시_사용_가능하다() {
        failEvery5Seconds(5);

        availability.reportHealth(true);

        assertThat(availability.status()).isEqualTo(new AiAvailability.Status(true, null));
    }

    @Test
    void 요약_워커가_재시도_끝에_멈추면_바로_멈춘_것으로_보고_60초는_상태_확인_성공으로_풀지_않는다() {
        availability.reportCallGaveUp();
        Instant since = clock.instant();

        clock.advance(Duration.ofSeconds(30));
        availability.reportHealth(true);
        assertThat(availability.status()).isEqualTo(new AiAvailability.Status(false, since));

        clock.advance(Duration.ofSeconds(30));
        assertThat(availability.status()).isEqualTo(new AiAvailability.Status(true, null));
    }

    @Test
    void 요약_워커가_계속_멈추면_첫_멈춤_시각을_유지한다() {
        availability.reportCallGaveUp();
        Instant since = clock.instant();

        clock.advance(Duration.ofSeconds(50));
        availability.reportCallGaveUp();
        clock.advance(Duration.ofSeconds(50));

        assertThat(availability.status()).isEqualTo(new AiAvailability.Status(false, since));
    }

    @Test
    void 요약_호출이_성공하면_멈춤이_바로_풀린다() {
        availability.reportCallGaveUp();

        availability.reportCallSucceeded();

        assertThat(availability.status().available()).isTrue();
    }

    @Test
    void 두_장애가_겹치면_먼저_시작한_시각을_보여준다() {
        failEvery5Seconds(4);
        Instant firstFailure = clock.instant().minusSeconds(15);

        availability.reportCallGaveUp();

        assertThat(availability.status()).isEqualTo(new AiAvailability.Status(false, firstFailure));
    }

    private void failEvery5Seconds(int times) {
        for (int i = 0; i < times; i++) {
            if (i > 0 || clock.instant().isAfter(NOW.atZone(MutableClock.KST).toInstant())) {
                clock.advance(Duration.ofSeconds(5));
            }
            availability.reportHealth(false);
        }
    }
}
