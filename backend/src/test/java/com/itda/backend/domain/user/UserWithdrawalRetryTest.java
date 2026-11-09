package com.itda.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.fixture.UserFixture;

/** 카카오 연결 해제 재시도 간격: 마지막 호출 뒤 1 · 2 · 5 · 10 · 30 · 60분(이후 60분). */
class UserWithdrawalRetryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 9, 10, 0);

    @Test
    void 실패가_늘수록_간격이_늘고_60분에서_멈춘다() {
        User user = requested();
        long[] expected = {1, 2, 5, 10, 30, 60, 60};
        for (long minutes : expected) {
            user.countKakaoUnlinkFailure();
            assertThat(user.isDueForRetry(AT.plusMinutes(minutes).minusSeconds(1))).isFalse();
            assertThat(user.isDueForRetry(AT.plusMinutes(minutes))).isTrue();
        }
    }

    @Test
    void 실패가_없거나_정리만_남았으면_늘_차례다() {
        User user = requested();
        assertThat(user.isDueForRetry(AT)).isTrue();
        user.countKakaoUnlinkFailure();
        user.markKakaoUnlinked();
        assertThat(user.isDueForRetry(AT)).isTrue();
    }

    @Test
    void 다시_신청하면_횟수를_지운다() {
        User user = requested();
        user.countKakaoUnlinkFailure();
        user.cancelWithdrawal();
        user.requestWithdrawal(AT);
        assertThat(user.kakaoUnlinkFailureCount()).isZero();
    }

    private static User requested() {
        User user = UserFixture.parent("123");
        ReflectionTestUtils.setField(user, "id", 1L);
        user.requestWithdrawal(AT);
        user.markKakaoUnlinkAttempt(AT);
        return user;
    }
}
