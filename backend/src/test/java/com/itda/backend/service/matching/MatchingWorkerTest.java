package com.itda.backend.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.itda.backend.dto.request.MatchingAgentRequest;
import com.itda.backend.exception.MatchingAgentException;

@ExtendWith(MockitoExtension.class)
class MatchingWorkerTest {

    private static final MatchingProperties PROPERTIES = new MatchingProperties("http://ai:8000",
            Duration.ofSeconds(3), Duration.ofSeconds(65), List.of(), new MatchingProperties.Worker(true, 5000, 10));

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
    void 한_건이_실패해도_실패로_남기고_다음_건을_계속_처리한다() {
        MatchingAgentReply reply2 = new MatchingAgentReply(null, "{}");
        given(matchingService.claimPending(10)).willReturn(List.of(1L, 2L));
        given(matchingService.prepareRequest(1L)).willReturn(requestFor(1L));
        given(matchingService.prepareRequest(2L)).willReturn(requestFor(2L));
        given(client.match(requestFor(1L))).willThrow(new MatchingAgentException("timeout"));
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
}
