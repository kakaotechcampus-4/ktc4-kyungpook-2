package com.itda.backend.service.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 실행기 줄. 같은 회원을 줄에 두 번 세우지 않되, 이미 돌기 시작한 작업은 새 접수에 다시 쓰지 않는다. */
class WithdrawalExecutorTest {

    private final WithdrawalSteps steps = mock(WithdrawalSteps.class);
    private final KakaoUnlinkClient kakao = mock(KakaoUnlinkClient.class);
    private final WithdrawalExecutor executor = new WithdrawalExecutor(new LocalWithdrawalLocks(), steps, kakao);

    @AfterEach
    void stop() {
        executor.destroy();
    }

    /**
     * 첫 작업이 상태를 읽고 접수를 취소하는 중에(아직 줄에서 빠지기 전) 다시 신청했다. 새 접수는 새 작업으로 서서 처리돼야 한다 —
     * 끝나 가는 첫 작업을 돌려주면 새 접수는 아무도 처리하지 않는다.
     */
    @Test
    void 돌고_있는_작업은_다시_쓰지_않고_새_작업을_세운다() throws Exception {
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(steps.prepare(anyLong())).willAnswer(invocation -> {
            firstRunning.countDown();
            release.await(5, TimeUnit.SECONDS);
            return WithdrawalSteps.Plan.nothing();
        }).willReturn(WithdrawalSteps.Plan.nothing());

        Future<Void> first = executor.submit(7L);
        assertThat(firstRunning.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Void> second = executor.submit(7L);
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        assertThat(second).isNotSameAs(first);
        verify(steps, times(2)).prepare(7L);
    }

    @Test
    void 아직_시작하지_않은_작업이_서_있으면_그것을_돌려준다() throws Exception {
        CountDownLatch blockerRunning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(steps.prepare(1L)).willAnswer(invocation -> {
            blockerRunning.countDown();
            release.await(5, TimeUnit.SECONDS);
            return WithdrawalSteps.Plan.nothing();
        });
        given(steps.prepare(2L)).willReturn(WithdrawalSteps.Plan.nothing());

        executor.submit(1L);
        assertThat(blockerRunning.await(5, TimeUnit.SECONDS)).isTrue();
        // 스레드 하나라 1번이 도는 동안 2번은 줄에서 기다린다
        Future<Void> queued = executor.submit(2L);
        Future<Void> again = executor.submit(2L);
        release.countDown();
        queued.get(5, TimeUnit.SECONDS);

        assertThat(again).isSameAs(queued);
        verify(steps, times(1)).prepare(2L);
    }
}
