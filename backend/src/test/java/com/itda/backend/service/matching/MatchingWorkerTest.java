package com.itda.backend.service.matching;

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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.itda.backend.dto.request.MatchingAgentRequest;
import com.itda.backend.exception.AiAgentException;
import com.itda.backend.exception.AiAgentUnavailableException;
import com.itda.backend.exception.MatchingTargetException;
import com.itda.backend.service.agent.WorkerProperties;

@ExtendWith(MockitoExtension.class)
class MatchingWorkerTest {

    private static final MatchingProperties PROPERTIES = new MatchingProperties(new WorkerProperties(true, 5000, 10));

    @Mock
    private MatchingService matchingService;
    @Mock
    private MatchingAgentClient client;
    @Mock
    private MatchingResultRecorder recorder;

    private MatchingWorker worker;

    @BeforeEach
    void setUp() {
        worker = new MatchingWorker(matchingService, client, recorder, PROPERTIES);
        lenient().when(client.isAvailable()).thenReturn(true);
    }

    private MatchingAgentRequest requestFor(Long id) {
        return new MatchingAgentRequest(id, "기록", List.of(), 1L, null, null, null);
    }

    @Test
    void 집어_간_일지를_순서대로_호출하고_결과를_남긴다() {
        MatchingAgentReply reply1 = new MatchingAgentReply(null, "{}");
        MatchingAgentReply reply2 = new MatchingAgentReply(null, "{}");
        given(matchingService.claimPending(10)).willReturn(List.of(1L, 2L));
        given(matchingService.prepareRequest(1L)).willReturn(requestFor(1L));
        given(matchingService.prepareRequest(2L)).willReturn(requestFor(2L));
        given(client.match(requestFor(1L))).willReturn(reply1);
        given(client.match(requestFor(2L))).willReturn(reply2);

        worker.run();

        InOrder order = inOrder(client, recorder);
        order.verify(client).match(requestFor(1L));
        order.verify(recorder).record(1L, reply1);
        order.verify(client).match(requestFor(2L));
        order.verify(recorder).record(2L, reply2);
    }

    @Test
    void AI_상태와_무관한_실패는_그_건만_실패로_남기고_다음_건을_계속_처리한다() {
        MatchingAgentReply reply2 = new MatchingAgentReply(null, "{}");
        given(matchingService.claimPending(10)).willReturn(List.of(1L, 2L));
        given(matchingService.prepareRequest(1L)).willReturn(requestFor(1L));
        given(matchingService.prepareRequest(2L)).willReturn(requestFor(2L));
        given(client.match(requestFor(1L))).willThrow(new AiAgentException("422 from agent"));
        given(client.match(requestFor(2L))).willReturn(reply2);

        worker.run();

        verify(recorder).recordFailure(1L);
        verify(recorder, never()).record(eq(1L), any());
        verify(recorder).record(2L, reply2);
        verify(recorder, never()).recordFailure(2L);
    }

    @Test
    void 결과_저장이_실패해도_실패로_남긴다() {
        MatchingAgentReply reply = new MatchingAgentReply(null, "{}");
        given(matchingService.claimPending(10)).willReturn(List.of(1L));
        given(matchingService.prepareRequest(1L)).willReturn(requestFor(1L));
        given(client.match(requestFor(1L))).willReturn(reply);
        willThrow(new IllegalArgumentException("auto without child"))
                .given(recorder).record(1L, reply);

        worker.run();

        verify(recorder).recordFailure(1L);
    }

    @Test
    void 멈춘_일지_되돌리기는_첫_실행에서_집기_전에_한_번만_한다() {
        given(matchingService.claimPending(anyInt())).willReturn(List.of());

        worker.run();
        worker.run();

        InOrder order = inOrder(matchingService);
        order.verify(matchingService).releaseStuck();
        order.verify(matchingService, times(2)).claimPending(10);
        verify(matchingService, times(1)).releaseStuck();
    }

    @Test
    void 설정으로_끄면_워커가_만들어지지_않는다() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(MatchingService.class, () -> mock(MatchingService.class))
                .withBean(MatchingAgentClient.class, () -> mock(MatchingAgentClient.class))
                .withBean(MatchingResultRecorder.class, () -> mock(MatchingResultRecorder.class))
                .withBean(MatchingProperties.class, () -> PROPERTIES)
                .withUserConfiguration(MatchingWorker.class);

        runner.withPropertyValues("app.matching.worker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MatchingWorker.class));
        runner.withPropertyValues("app.matching.worker.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(MatchingWorker.class));
    }

    @Test
    void 원본이_없는_일지도_그_건만_실패로_남긴다() {
        given(matchingService.claimPending(10)).willReturn(List.of(1L, 2L));
        given(matchingService.prepareRequest(1L)).willThrow(new MatchingTargetException("raw record not found"));
        given(matchingService.prepareRequest(2L)).willReturn(requestFor(2L));

        worker.run();

        verify(recorder).recordFailure(1L);
        verify(client).match(requestFor(2L));
    }

    @Test
    void AI가_응답하지_않으면_일지를_집지_않는다() {
        given(client.isAvailable()).willReturn(false);

        worker.run();

        verify(matchingService, never()).claimPending(anyInt());
        verify(client, never()).match(any());
    }

    @Test
    void 재시도까지_실패하면_그_건만_실패로_남기고_나머지는_대기로_돌려놓고_멈춘다() {
        given(matchingService.claimPending(10)).willReturn(List.of(1L, 2L, 3L));
        given(matchingService.prepareRequest(1L)).willReturn(requestFor(1L));
        given(client.match(requestFor(1L))).willThrow(new AiAgentUnavailableException("down", null));

        worker.run();

        verify(recorder).recordFailure(1L);
        verify(matchingService).release(List.of(2L, 3L));
        verify(matchingService, never()).prepareRequest(2L);
        verify(matchingService, never()).prepareRequest(3L);
    }

    @Test
    void 앱이_종료되는_중이면_실패로_남기지_않고_멈춘다() {
        given(matchingService.claimPending(10)).willReturn(List.of(1L, 2L));
        given(matchingService.prepareRequest(1L)).willReturn(requestFor(1L));
        given(client.match(requestFor(1L))).willAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new AiAgentException("interrupted while waiting to retry");
        });

        try {
            worker.run();
        } finally {
            Thread.interrupted(); // 다른 테스트에 영향이 없도록 인터럽트 표시를 지운다
        }

        // 두 건 모두 MATCHING 으로 남기고, 다음에 켜질 때 releaseStuck 이 되돌린다.
        verify(recorder, never()).recordFailure(any());
        verify(matchingService, never()).prepareRequest(2L);
        verify(matchingService, never()).release(any());
    }
}
