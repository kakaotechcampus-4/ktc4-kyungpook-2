package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.itda.backend.dto.request.SummaryAgentRequest;
import com.itda.backend.exception.AiAgentException;
import com.itda.backend.exception.AiAgentUnavailableException;
import com.itda.backend.exception.SummaryTargetException;
import com.itda.backend.service.agent.WorkerProperties;

@ExtendWith(MockitoExtension.class)
class SummaryWorkerTest {

    private static final SummaryProperties PROPERTIES = new SummaryProperties(new WorkerProperties(true, 5000, 10),
            LocalTime.of(3, 0), Duration.ofMinutes(30), ZoneId.of("Asia/Seoul"), Duration.ofSeconds(120));

    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);

    @Mock
    private SummaryService summaryService;
    @Mock
    private SummaryAgentClient client;
    @Mock
    private SummaryResultRecorder recorder;

    private SummaryWorker worker;

    @BeforeEach
    void setUp() {
        worker = new SummaryWorker(summaryService, client, recorder, PROPERTIES);
        lenient().when(client.isAvailable()).thenReturn(true);
    }

    private ClaimedSummaryGroup group(long childId, Long... entryIds) {
        return new ClaimedSummaryGroup(new SummaryGroup(childId, DATE, 3L), List.of(entryIds));
    }

    private SummaryAgentRequest requestFor(ClaimedSummaryGroup claimed) {
        return new SummaryAgentRequest(claimed.group().childId(), "아이", "2026-10-08", 3L, "학교",
                claimed.journalEntryIds().stream()
                        .map(id -> new SummaryAgentRequest.Source(id, "기록", "2026-10-08")).toList(),
                List.of());
    }

    @Test
    void 집어_간_묶음을_순서대로_호출하고_결과를_남긴다() {
        ClaimedSummaryGroup first = group(8L, 1L, 2L);
        ClaimedSummaryGroup second = group(9L, 3L);
        SummaryAgentReply reply1 = new SummaryAgentReply(null, "{}");
        SummaryAgentReply reply2 = new SummaryAgentReply(null, "{}");
        given(summaryService.claimReadyGroups(10)).willReturn(List.of(first, second));
        given(summaryService.prepareRequest(first)).willReturn(requestFor(first));
        given(summaryService.prepareRequest(second)).willReturn(requestFor(second));
        given(client.summarize(requestFor(first))).willReturn(reply1);
        given(client.summarize(requestFor(second))).willReturn(reply2);

        worker.run();

        InOrder order = inOrder(client, recorder);
        order.verify(client).summarize(requestFor(first));
        order.verify(recorder).record(first, requestFor(first), reply1);
        order.verify(client).summarize(requestFor(second));
        order.verify(recorder).record(second, requestFor(second), reply2);
    }

    @Test
    void AI_상태와_무관한_실패는_그_묶음만_실패로_남기고_다음_묶음을_계속_처리한다() {
        ClaimedSummaryGroup first = group(8L, 1L);
        ClaimedSummaryGroup second = group(9L, 2L);
        SummaryAgentReply reply2 = new SummaryAgentReply(null, "{}");
        given(summaryService.claimReadyGroups(10)).willReturn(List.of(first, second));
        given(summaryService.prepareRequest(first)).willReturn(requestFor(first));
        given(summaryService.prepareRequest(second)).willReturn(requestFor(second));
        given(client.summarize(requestFor(first))).willThrow(new AiAgentException("422 from agent"));
        given(client.summarize(requestFor(second))).willReturn(reply2);

        worker.run();

        verify(recorder).recordFailure(first);
        verify(recorder, never()).record(eq(first), any(), any());
        verify(recorder).record(second, requestFor(second), reply2);
        verify(recorder, never()).recordFailure(second);
    }

    @Test
    void 결과_저장이_실패해도_실패로_남긴다() {
        ClaimedSummaryGroup claimed = group(8L, 1L);
        SummaryAgentReply reply = new SummaryAgentReply(null, "{}");
        given(summaryService.claimReadyGroups(10)).willReturn(List.of(claimed));
        given(summaryService.prepareRequest(claimed)).willReturn(requestFor(claimed));
        given(client.summarize(requestFor(claimed))).willReturn(reply);
        willThrow(new IllegalArgumentException("content missing")).given(recorder).record(claimed, requestFor(claimed), reply);

        worker.run();

        verify(recorder).recordFailure(claimed);
    }

    @Test
    void 요청을_만들_수_없는_묶음도_그_묶음만_실패로_남긴다() {
        ClaimedSummaryGroup first = group(8L, 1L);
        ClaimedSummaryGroup second = group(9L, 2L);
        given(summaryService.claimReadyGroups(10)).willReturn(List.of(first, second));
        given(summaryService.prepareRequest(first)).willThrow(new SummaryTargetException("no journal entry left"));
        given(summaryService.prepareRequest(second)).willReturn(requestFor(second));

        worker.run();

        verify(recorder).recordFailure(first);
        verify(client).summarize(requestFor(second));
    }

    @Test
    void 멈춘_일지_되돌리기는_첫_실행에서_집기_전에_한_번만_한다() {
        given(summaryService.claimReadyGroups(anyInt())).willReturn(List.of());

        worker.run();
        worker.run();

        InOrder order = inOrder(summaryService);
        order.verify(summaryService).releaseStuck();
        order.verify(summaryService, times(2)).claimReadyGroups(10);
        verify(summaryService, times(1)).releaseStuck();
    }

    @Test
    void AI가_응답하지_않으면_묶음을_집지_않는다() {
        given(client.isAvailable()).willReturn(false);

        worker.run();

        verify(summaryService, never()).claimReadyGroups(anyInt());
        verify(client, never()).summarize(any());
    }

    @Test
    void 재시도까지_실패하면_실패로_남기지_않고_그_묶음까지_되돌려놓고_멈춘다() {
        // 요약 단계의 FAILED 는 다시 처리할 길이 없다. AI·LLM 이 잠깐 죽은 것이면 다음 차례에 다시 요약하게 둔다.
        ClaimedSummaryGroup first = group(8L, 1L);
        ClaimedSummaryGroup second = group(9L, 2L);
        ClaimedSummaryGroup third = group(10L, 3L);
        given(summaryService.claimReadyGroups(10)).willReturn(List.of(first, second, third));
        given(summaryService.prepareRequest(first)).willReturn(requestFor(first));
        given(client.summarize(requestFor(first))).willThrow(new AiAgentUnavailableException("down", null));

        worker.run();

        verify(recorder, never()).recordFailure(any());
        verify(summaryService).release(List.of(first, second, third));
        verify(summaryService, never()).prepareRequest(second);
        verify(summaryService, never()).prepareRequest(third);
    }

    @Test
    void 앱이_종료되는_중이면_실패로_남기지_않고_멈춘다() {
        ClaimedSummaryGroup first = group(8L, 1L);
        ClaimedSummaryGroup second = group(9L, 2L);
        given(summaryService.claimReadyGroups(10)).willReturn(List.of(first, second));
        given(summaryService.prepareRequest(first)).willReturn(requestFor(first));
        given(client.summarize(requestFor(first))).willAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new AiAgentException("interrupted while waiting to retry");
        });

        try {
            worker.run();
        } finally {
            Thread.interrupted(); // 다른 테스트에 영향이 없도록 인터럽트 표시를 지운다
        }

        // 두 묶음 모두 SUMMARIZING 으로 남기고, 다음에 켜질 때 releaseStuck 이 되돌린다.
        verify(recorder, never()).recordFailure(any());
        verify(summaryService, never()).prepareRequest(second);
        verify(summaryService, never()).release(any());
    }

    @Test
    void 설정으로_끄면_워커가_만들어지지_않는다() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(SummaryService.class, () -> mock(SummaryService.class))
                .withBean(SummaryAgentClient.class, () -> mock(SummaryAgentClient.class))
                .withBean(SummaryResultRecorder.class, () -> mock(SummaryResultRecorder.class))
                .withBean(SummaryProperties.class, () -> PROPERTIES)
                .withUserConfiguration(SummaryWorker.class);

        runner.withPropertyValues("app.summary.worker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SummaryWorker.class));
        runner.withPropertyValues("app.summary.worker.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(SummaryWorker.class));
    }
}
