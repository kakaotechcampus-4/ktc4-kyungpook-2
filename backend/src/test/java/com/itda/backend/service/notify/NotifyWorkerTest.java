package com.itda.backend.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.itda.backend.domain.insight.InsightNotification;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.dto.request.notify.NotifyAgentRequest;
import com.itda.backend.dto.response.notify.NotifyAgentResponse;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.repository.insight.ApprovedInsightPeriod;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.service.agent.AiAvailability;
import com.itda.backend.service.agent.WorkerProperties;
import com.itda.backend.service.insight.InsightProperties;

@ExtendWith(MockitoExtension.class)
class NotifyWorkerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final LocalDate TO = LocalDate.of(2026, 4, 7);
    private static final Long CHILD = 12L;

    private static final NotifyProperties PROPERTIES =
            new NotifyProperties(new WorkerProperties(true, 5000, 10), List.of(), Duration.ofSeconds(90), 0);
    private static final InsightProperties INSIGHT_PROPERTIES = new InsightProperties(
            new WorkerProperties(true, 5000, 10), 7, SEOUL, 14, false, List.of(), Duration.ofSeconds(210), 1);

    @Mock
    private NotifyService notifyService;
    @Mock
    private NotifyAgentClient client;
    @Mock
    private NotifyRecorder recorder;
    @Mock
    private InsightResultRepository insightResultRepository;
    @Mock
    private AiAvailability availability;

    private NotifyWorker worker;

    @BeforeEach
    void setUp() {
        worker = new NotifyWorker(notifyService, client, recorder, insightResultRepository,
                INSIGHT_PROPERTIES, PROPERTIES, availability);
        lenient().when(client.isAvailable()).thenReturn(true);
        lenient().when(recorder.releaseStuck()).thenReturn(0);
        lenient().when(insightResultRepository.findApprovedPeriodsSince(eq(InsightStatus.APPROVED), any()))
                .thenReturn(List.of(new ApprovedInsightPeriod(CHILD, FROM, TO)));
    }

    private PreparedNotify prepared(long lastInsightId) {
        NotifyAgentRequest request = new NotifyAgentRequest(CHILD, "김지후", FROM.toString(), TO.toString(),
                List.of(new NotifyAgentRequest.ApprovedInsight("본문", "센터1", "2026년 4월 1주차",
                        List.of("센터"), false, List.of())),
                null, List.of());
        return new PreparedNotify(request, 1, lastInsightId);
    }

    private InsightNotification claimed() {
        return InsightNotification.claim(CHILD, FROM, TO, 1);
    }

    private static NotifyAgentResponse sent() {
        return new NotifyAgentResponse(CHILD, "key", "sent", "itda-1", false, List.of(), true);
    }

    @Test
    void 승인된_인사이트가_없으면_집지_않는다() {
        // 집어서 SENT 로 남기면, 그 뒤에 교사가 승인해도 그 기간을 다시 보지 않는다.
        given(notifyService.prepare(CHILD, FROM, TO)).willReturn(Optional.empty());

        worker.run();

        verify(recorder, never()).claim(anyLong(), any(), any());
        verify(client, never()).send(any());
    }

    @Test
    void 이미_보낸_기간은_집지_않는다() {
        given(notifyService.prepare(CHILD, FROM, TO)).willReturn(Optional.of(prepared(99L)));
        given(recorder.needsRun(CHILD, FROM, TO, 99L)).willReturn(false);

        worker.run();

        verify(recorder, never()).claim(anyLong(), any(), any());
    }

    @Test
    void 보내고_결과를_남긴다() {
        given(notifyService.prepare(CHILD, FROM, TO)).willReturn(Optional.of(prepared(99L)));
        given(recorder.needsRun(CHILD, FROM, TO, 99L)).willReturn(true);
        given(recorder.claim(CHILD, FROM, TO)).willReturn(Optional.of(claimed()));
        given(client.send(any())).willReturn(sent());

        worker.run();

        verify(recorder).record(any(), eq(sent()), eq(1), eq(99L));
        verify(availability).reportCallSucceeded();
    }

    @Test
    void 멱등성_키는_판_번호가_아니라_재료에_묶인다() {
        // 앱이 메일을 보낸 직후 죽으면 판 번호가 올라간 채 다시 집는다. 그때도 키가 같아야
        // AI 쪽 MCP 서버가 두 번째 발송을 막는다.
        given(notifyService.prepare(CHILD, FROM, TO)).willReturn(Optional.of(prepared(99L)));
        given(recorder.needsRun(CHILD, FROM, TO, 99L)).willReturn(true);
        given(recorder.claim(CHILD, FROM, TO)).willReturn(Optional.of(claimed()));
        given(client.send(any())).willReturn(sent());

        worker.run();

        ArgumentCaptor<NotifyAgentRequest> captor = ArgumentCaptor.forClass(NotifyAgentRequest.class);
        verify(client).send(captor.capture());
        assertThat(captor.getValue().notificationId()).isEqualTo("12:2026-04-01:2026-04-07:99");
    }

    @Test
    void 재료가_늘면_키가_달라진다() {
        // 뒤에 인사이트가 더 승인되면 새 메일이 맞다. 그때는 MCP 가 막으면 안 된다.
        NotifyWorker.Period period = new NotifyWorker.Period(CHILD, FROM, TO);
        assertThat(NotifyWorker.notificationIdOf(period, 99L))
                .isNotEqualTo(NotifyWorker.notificationIdOf(period, 100L));
    }

    @Test
    void AI_가_응답하지_않으면_그_기간만_실패로_두고_멈춘다() {
        given(notifyService.prepare(CHILD, FROM, TO)).willReturn(Optional.of(prepared(99L)));
        given(recorder.needsRun(CHILD, FROM, TO, 99L)).willReturn(true);
        given(recorder.claim(CHILD, FROM, TO)).willReturn(Optional.of(claimed()));
        willThrow(new AiAgentUnavailableException("죽음", null)).given(client).send(any());
        given(client.failureOf(any())).willReturn("delivery_failed");

        worker.run();

        verify(recorder).recordFailure(any(), eq("delivery_failed"));
        // /health 는 LLM 까지 보지 않으므로 여기서 멈춘 것을 장애 배너에 따로 알린다.
        verify(availability).reportCallGaveUp();
    }

    @Test
    void 집기_경합에_지면_건너뛴다() {
        given(notifyService.prepare(CHILD, FROM, TO)).willReturn(Optional.of(prepared(99L)));
        given(recorder.needsRun(CHILD, FROM, TO, 99L)).willReturn(true);
        willThrow(new DataIntegrityViolationException("uk")).given(recorder).claim(CHILD, FROM, TO);

        worker.run();

        verify(client, never()).send(any());
    }

    @Test
    void AI_가_꺼져_있으면_아무것도_하지_않는다() {
        given(client.isAvailable()).willReturn(false);

        worker.run();

        verify(notifyService, never()).prepare(anyLong(), any(), any());
        verify(availability).reportHealth(false);
    }
}
