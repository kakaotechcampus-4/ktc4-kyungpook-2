package com.itda.backend.service.user;

import java.util.Optional;

/**
 * 회원 한 명의 탈퇴 실행을 한 줄로 세우는 잠금. "호출 기록 → 카카오 호출 → 결과 기록 → DB 정리" 전체를 감싼다.
 *
 * <p>시간이 지났다고 다른 실행이 넘겨받지 않는다. 잠금은 쥔 쪽이 끝내거나 그 세션이 끊길 때만 풀린다 — 카카오는 우리 쪽
 * 처리 번호를 확인해 주지 않으므로, 처리 번호로는 멈췄던 실행이 나중에 카카오를 부르는 것을 막을 수 없다.
 */
public interface WithdrawalLocks {

    /** 잠그면 쥔 잠금을, 다른 실행이 쥐고 있으면 비어 있는 값을 돌려준다. 기다리지 않는다. */
    Optional<Held> tryAcquire(long userId);

    interface Held extends AutoCloseable {

        /** 잠금이 아직 살아 있는지. 카카오를 부르기 직전에 본다 — 가능성을 줄일 뿐 틈을 없애지는 못한다. */
        boolean isAlive();

        /** 잠금을 푼다. 예외를 던지지 않는다. */
        @Override
        void close();
    }
}
