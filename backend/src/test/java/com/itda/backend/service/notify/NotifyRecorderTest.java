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

/**
 * 집기 조건과 결과 기록. <b>여기가 "메일이 두 번 나가나 / 영영 안 나가나" 를 가르는 자리다.</b>
 */
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
        // 교사가 Gate 2 승인을 틈틈이 한다. "이 기간은 보냈다" 만 기억하면 뒤에 승인된 글이 영영 안 나간다.
        InsightNotification sent = notification();
        sent.markSent(1, 99L, "itda-1");
        lastIs(sent);

        assertThat(recorder.needsRun(CHILD, FROM, TO, 100L)).isTrue();
    }

    @Test
    void 막힌_것은_재료가_그대로면_다시_집지_않는다() {
        // 재료가 그대로면 다시 써도 같은 글이 나온다. 교사가 고쳐야 바뀐다.
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
        lastIs(notification());  // claim 직후는 RUNNING

        assertThat(recorder.needsRun(CHILD, FROM, TO, 99L)).isFalse();
    }

    @Test
    void 눈금을_모르면_다시_집는다() {
        // lastInsightId 가 null 인 행은 이 컬럼이 생기기 전 것이다. 모르면 다시 본다.
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
        // 다시 집지 않으므로 FAILED 와 섞으면 안 된다. 교사가 봐야 할 일이다.
        InsightNotification notification = notification();
        given(repository.findById(any())).willReturn(Optional.of(notification));
        NotifyAgentResponse response = new NotifyAgentResponse(CHILD, "key", "blocked", null, false,
                List.of(new NotifyAgentResponse.BlockedLine("박서연이와 놀았어요", List.of("다른아이이름"))), true);

        recorder.record(notification, response, 1, 99L);

        assertThat(notification.getStatus().name()).isEqualTo("BLOCKED");
        assertThat(notification.getFailureReason()).isEqualTo("다른아이이름");
        // ⚠️ 막힌 문장은 저장하지 않는다. 아이 기록이라 DB 에 옮겨 적지 않는다.
        assertThat(notification.getFailureReason()).doesNotContain("박서연");
    }

    @Test
    void 받을_사람이_없으면_SENT_가_아니다() {
        // SENT 로 적으면 교사 화면에 "보냈다" 고 뜨는데 실제로는 아무에게도 안 갔다 (#430 리뷰).
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
        // 그사이 보호자가 이메일을 넣었을 수 있다.
        InsightNotification none = notification();
        none.markNoRecipient(1, 99L);
        lastIs(none);

        assertThat(recorder.needsRun(CHILD, FROM, TO, 100L)).isTrue();
    }
}
