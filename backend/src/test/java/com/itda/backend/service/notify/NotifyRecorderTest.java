package com.itda.backend.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.itda.backend.domain.insight.InsightNotification;
import com.itda.backend.dto.response.notify.NotifyAgentResponse;
import com.itda.backend.repository.insight.InsightNotificationRepository;
import com.itda.backend.service.agent.WorkerProperties;

@ExtendWith(MockitoExtension.class)
class NotifyRecorderTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final LocalDate TO = LocalDate.of(2026, 4, 7);
    private static final Long CHILD = 12L;

    @Mock
    private InsightNotificationRepository repository;

    private NotifyRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new NotifyRecorder(repository,
                new NotifyProperties(new WorkerProperties(true, 5000, 10), List.of(),
                        Duration.ofSeconds(90), 0));
    }

    private InsightNotification notification() {
        return InsightNotification.claim(CHILD, FROM, TO, 1);
    }

    private void lastIs(InsightNotification notification) {
        given(repository.findFirstByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(CHILD, FROM, TO))
                .willReturn(Optional.ofNullable(notification));
    }

    @Test
    void 처음이면_집는다() {
        lastIs(null);

        assertThat(recorder.needsRun(CHILD, FROM, TO, 99L)).isTrue();
    }

    @Test
    void 이미_보냈고_재료가_그대로면_집지_않는다() {
        InsightNotification sent = notification();
        sent.markSent(1, 99L, "itda-1");
        lastIs(sent);

        assertThat(recorder.needsRun(CHILD, FROM, TO, 99L)).isFalse();
    }

    @Test
    void 이미_보냈어도_인사이트가_더_승인되면_다시_집는다() {
        InsightNotification sent = notification();
        sent.markSent(1, 99L, "itda-1");
        lastIs(sent);

        assertThat(recorder.needsRun(CHILD, FROM, TO, 100L)).isTrue();
    }

    @Test
    void 막힌_것은_재료가_그대로면_다시_집지_않는다() {
        InsightNotification blocked = notification();
        blocked.markBlocked(1, 99L, "다른아이이름");
        lastIs(blocked);

        assertThat(recorder.needsRun(CHILD, FROM, TO, 99L)).isFalse();
    }

    @Test
    void 막힌_뒤에_인사이트가_더_승인되면_다시_집는다() {
        InsightNotification blocked = notification();
        blocked.markBlocked(1, 99L, "다른아이이름");
        lastIs(blocked);

        assertThat(recorder.needsRun(CHILD, FROM, TO, 100L)).isTrue();
    }

    @Test
    void 누가_돌고_있으면_집지_않는다() {
        lastIs(notification());

        assertThat(recorder.needsRun(CHILD, FROM, TO, 99L)).isFalse();
    }

    @Test
    void 눈금을_모르면_다시_집는다() {
        InsightNotification sent = notification();
        sent.markSent(1, null, "itda-1");
        lastIs(sent);

        assertThat(recorder.needsRun(CHILD, FROM, TO, 99L)).isTrue();
    }

    @Test
    void 실패한_것은_기다리는_시간이_없으면_바로_다시_집는다() {
        InsightNotification failed = notification();
        failed.fail("delivery_failed");
        lastIs(failed);

        assertThat(recorder.needsRun(CHILD, FROM, TO, 99L)).isTrue();
    }

    @Test
    void 막힘은_실패가_아니라_blocked_로_남긴다() {
        InsightNotification notification = notification();
        given(repository.findById(any())).willReturn(Optional.of(notification));
        NotifyAgentResponse response = new NotifyAgentResponse(CHILD, "key", "blocked", null, false,
                List.of(new NotifyAgentResponse.BlockedLine("박서연이와 놀았어요", List.of("다른아이이름"))), true);

        recorder.record(notification, response, 1, 99L);

        assertThat(notification.getStatus().name()).isEqualTo("BLOCKED");
        assertThat(notification.getFailureReason()).isEqualTo("다른아이이름");
        assertThat(notification.getFailureReason()).doesNotContain("박서연");
    }

    @Test
    void 받을_사람이_없으면_SENT_가_아니다() {
        InsightNotification notification = notification();
        given(repository.findById(any())).willReturn(Optional.of(notification));
        NotifyAgentResponse response = new NotifyAgentResponse(CHILD, "key", "no_recipient", null, false,
                List.of(), true);

        recorder.record(notification, response, 1, 99L);

        assertThat(notification.getStatus().name()).isEqualTo("NO_RECIPIENT");
        assertThat(notification.getMessageId()).isNull();
    }

    @Test
    void 받을_사람이_없었어도_인사이트가_더_승인되면_다시_집는다() {
        InsightNotification none = notification();
        none.markNoRecipient(1, 99L);
        lastIs(none);

        assertThat(recorder.needsRun(CHILD, FROM, TO, 100L)).isTrue();
    }
}
