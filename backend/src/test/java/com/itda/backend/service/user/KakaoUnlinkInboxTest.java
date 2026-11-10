package com.itda.backend.service.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.itda.backend.domain.user.KakaoUnlinkWebhook;
import com.itda.backend.repository.user.KakaoUnlinkWebhookRepository;

class KakaoUnlinkInboxTest {

    private final KakaoUnlinkWebhookRepository repository = mock(KakaoUnlinkWebhookRepository.class);
    private final KakaoUnlinkInbox inbox = new KakaoUnlinkInbox(
            repository, mock(WithdrawalSteps.class), mock(WithdrawalExecutor.class),
            mock(PlatformTransactionManager.class), Clock.systemDefaultZone());
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        inbox.destroy();
    }

    @Test
    void 저장이_늦어도_기한이_지나면_먼저_답하고_저장은_이어서_끝낸다() throws Exception {
        given(repository.save(any(KakaoUnlinkWebhook.class))).willAnswer(invocation -> {
            release.await(10, TimeUnit.SECONDS);
            return invocation.getArgument(0);
        });
        given(repository.findPendingIds(any())).willReturn(List.of());

        long started = System.nanoTime();
        boolean saved = inbox.receive("4242");
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(saved).isFalse();
        assertThat(elapsed).isBetween(KakaoUnlinkInbox.RECEIVE_WAIT_MILLIS - 100, KakaoUnlinkInbox.RECEIVE_WAIT_MILLIS + 900);

        release.countDown();
        verify(repository, timeout(5000)).findPendingIds(any());
    }

    @Test
    void 저장이_빠르면_남겼다고_답한다() {
        given(repository.save(any(KakaoUnlinkWebhook.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(repository.findPendingIds(any())).willReturn(List.of());

        assertThat(inbox.receive("4242")).isTrue();
    }
}
