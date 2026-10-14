package com.itda.backend.service.validation;

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

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.itda.backend.dto.request.validation.ValidationAgentRequest;
import com.itda.backend.exception.agent.AiAgentException;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.exception.validation.ValidationTargetException;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.service.agent.AgentDownProperties;
import com.itda.backend.service.agent.WorkerProperties;

@ExtendWith(MockitoExtension.class)
class ValidationWorkerTest {

    private static final AgentDownProperties AGENT_DOWN = new AgentDownProperties(2, Duration.ofSeconds(30));
    private static final ValidationProperties PROPERTIES = new ValidationProperties(new WorkerProperties(true, 5000, 10));

    @Mock
    private ValidationService validationService;
    @Mock
    private ValidationAgentClient client;
    @Mock
    private ValidationResultRecorder recorder;

    private ValidationWorker worker;
    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 10, 14, 10, 0));

    @BeforeEach
    void setUp() {
        worker = new ValidationWorker(validationService, client, recorder, PROPERTIES, AGENT_DOWN, clock);
        lenient().when(client.isAvailable()).thenReturn(true);
    }

    private ValidationTarget targetFor(Long id) {
        return new ValidationTarget(new ValidationAgentRequest(id, "기록", 8L, "임유진"), 70L + id);
    }

    @Test
    void 집어_간_일지를_순서대로_호출하고_결과를_남긴다() {
        ValidationAgentReply reply1 = new ValidationAgentReply(null, "{}");
        ValidationAgentReply reply2 = new ValidationAgentReply(null, "{}");
        given(validationService.claimMatched(10)).willReturn(List.of(1L, 2L));
        given(validationService.prepareRequest(1L)).willReturn(targetFor(1L));
        given(validationService.prepareRequest(2L)).willReturn(targetFor(2L));
        given(client.validate(targetFor(1L).request())).willReturn(reply1);
        given(client.validate(targetFor(2L).request())).willReturn(reply2);

        worker.run();

        InOrder order = inOrder(client, recorder);
        order.verify(client).validate(targetFor(1L).request());
        order.verify(recorder).record(targetFor(1L), reply1);
        order.verify(client).validate(targetFor(2L).request());
        order.verify(recorder).record(targetFor(2L), reply2);
    }

    @Test
    void AI_상태와_무관한_실패는_그_건만_실패로_남기고_다음_건을_계속_처리한다() {
        // 4xx, 응답 해석 실패가 여기에 해당한다.
        ValidationAgentReply reply2 = new ValidationAgentReply(null, "{}");
        given(validationService.claimMatched(10)).willReturn(List.of(1L, 2L));
        given(validationService.prepareRequest(1L)).willReturn(targetFor(1L));
        given(validationService.prepareRequest(2L)).willReturn(targetFor(2L));
        given(client.validate(targetFor(1L).request())).willThrow(new AiAgentException("422 from agent"));
        given(client.validate(targetFor(2L).request())).willReturn(reply2);

        worker.run();

        verify(recorder).recordFailure(1L);
        verify(recorder, never()).record(eq(targetFor(1L)), any());
        verify(recorder).record(targetFor(2L), reply2);
        verify(recorder, never()).recordFailure(2L);
    }

    @Test
    void 결과_저장이_실패해도_실패로_남긴다() {
        ValidationAgentReply reply = new ValidationAgentReply(null, "{}");
        given(validationService.claimMatched(10)).willReturn(List.of(1L));
        given(validationService.prepareRequest(1L)).willReturn(targetFor(1L));
        given(client.validate(targetFor(1L).request())).willReturn(reply);
        willThrow(new IllegalArgumentException("verdict missing")).given(recorder).record(targetFor(1L), reply);

        worker.run();

        verify(recorder).recordFailure(1L);
    }

    @Test
    void 요청을_만들_수_없는_일지도_그_건만_실패로_남긴다() {
        given(validationService.claimMatched(10)).willReturn(List.of(1L, 2L));
        given(validationService.prepareRequest(1L)).willThrow(new ValidationTargetException("journal entry not found"));
        given(validationService.prepareRequest(2L)).willReturn(targetFor(2L));

        worker.run();

        verify(recorder).recordFailure(1L);
        verify(client).validate(targetFor(2L).request());
    }

    @Test
    void 멈춘_일지_되돌리기는_첫_실행에서_집기_전에_한_번만_한다() {
        given(validationService.claimMatched(anyInt())).willReturn(List.of());

        worker.run();
        worker.run();

        InOrder order = inOrder(validationService);
        order.verify(validationService).releaseStuck();
        order.verify(validationService, times(2)).claimMatched(10);
        verify(validationService, times(1)).releaseStuck();
    }

    @Test
    void AI가_응답하지_않으면_일지를_집지_않는다() {
        given(client.isAvailable()).willReturn(false);

        worker.run();

        verify(validationService, never()).claimMatched(anyInt());
        verify(client, never()).validate(any());
    }

    @Test
    void AI_다운이면_처리_중이던_일지도_실패로_남기지_않고_나머지와_함께_검증_대기로_돌려놓고_멈춘다() {
        // LLM 만 죽어 있으면 /health 는 통과하고 AI 가 503 을 준다. 지금까지는 차례마다 1건씩 FAILED 로 빠졌다.
        given(validationService.claimMatched(10)).willReturn(List.of(1L, 2L, 3L));
        given(validationService.prepareRequest(1L)).willReturn(targetFor(1L));
        given(client.validate(targetFor(1L).request())).willThrow(new AiAgentUnavailableException("down", null));
        given(validationService.releaseAfterAgentDown(1L, 2)).willReturn(true);

        worker.run();

        verify(recorder, never()).recordFailure(any());
        verify(validationService).release(List.of(2L, 3L));
        verify(validationService, never()).prepareRequest(2L);
        verify(validationService, never()).prepareRequest(3L);
    }

    @Test
    void AI_다운으로_되돌린_횟수가_상한을_넘으면_그_건은_실패로_남긴다() {
        given(validationService.claimMatched(10)).willReturn(List.of(1L, 2L));
        given(validationService.prepareRequest(1L)).willReturn(targetFor(1L));
        given(client.validate(targetFor(1L).request())).willThrow(new AiAgentUnavailableException("down", null));
        given(validationService.releaseAfterAgentDown(1L, 2)).willReturn(false);
        given(validationService.prepareRequest(2L)).willReturn(targetFor(2L));

        worker.run();

        // 그 일지는 빠졌으니 대기열을 멈추지 않는다 — AI 가 이 일지만 거절하는 경우 나머지는 정상으로 처리된다.
        verify(recorder).recordFailure(1L);
        verify(validationService).prepareRequest(2L);
        verify(validationService, never()).release(any());
    }

    @Test
    void 앱이_종료되는_중이면_실패로_남기지_않고_멈춘다() {
        given(validationService.claimMatched(10)).willReturn(List.of(1L, 2L));
        given(validationService.prepareRequest(1L)).willReturn(targetFor(1L));
        given(client.validate(targetFor(1L).request())).willAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new AiAgentException("interrupted while waiting to retry");
        });

        try {
            worker.run();
        } finally {
            Thread.interrupted(); // 다른 테스트에 영향이 없도록 인터럽트 표시를 지운다
        }

        // 두 건 모두 VALIDATING 으로 남기고, 다음에 켜질 때 releaseStuck 이 되돌린다.
        verify(recorder, never()).recordFailure(any());
        verify(validationService, never()).prepareRequest(2L);
        verify(validationService, never()).release(any());
    }

    @Test
    void 설정으로_끄면_워커가_만들어지지_않는다() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(ValidationService.class, () -> mock(ValidationService.class))
                .withBean(ValidationAgentClient.class, () -> mock(ValidationAgentClient.class))
                .withBean(ValidationResultRecorder.class, () -> mock(ValidationResultRecorder.class))
                .withBean(ValidationProperties.class, () -> PROPERTIES)
                .withBean(AgentDownProperties.class, () -> AGENT_DOWN)
                .withBean(Clock.class, () -> clock)
                .withUserConfiguration(ValidationWorker.class);

        runner.withPropertyValues("app.validation.worker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ValidationWorker.class));
        runner.withPropertyValues("app.validation.worker.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ValidationWorker.class));
    }
    @Test
    void AI_다운_뒤_쉬는_동안은_AI_상태도_안_보고_일지를_안_집는다() {
        given(validationService.claimMatched(10)).willReturn(List.of(1L));
        given(validationService.prepareRequest(1L)).willReturn(targetFor(1L));
        given(client.validate(targetFor(1L).request())).willThrow(new AiAgentUnavailableException("down", null));
        given(validationService.releaseAfterAgentDown(1L, 2)).willReturn(true);
        worker.run();

        clock.advance(Duration.ofSeconds(29));
        worker.run();

        verify(validationService, times(1)).claimMatched(anyInt());
        verify(client, times(1)).isAvailable();
    }

    @Test
    void AI_다운_뒤_쉬는_시간이_지나면_다시_집는다() {
        given(validationService.claimMatched(10)).willReturn(List.of(1L), List.of());
        given(validationService.prepareRequest(1L)).willReturn(targetFor(1L));
        given(client.validate(targetFor(1L).request())).willThrow(new AiAgentUnavailableException("down", null));
        given(validationService.releaseAfterAgentDown(1L, 2)).willReturn(true);
        worker.run();

        clock.advance(Duration.ofSeconds(30));
        worker.run();

        verify(validationService, times(2)).claimMatched(10);
    }
    @Test
    void 나머지_일지를_되돌리다_실패해도_AI_다운_뒤에는_쉰다() {
        given(validationService.claimMatched(10)).willReturn(List.of(1L, 2L));
        given(validationService.prepareRequest(1L)).willReturn(targetFor(1L));
        given(client.validate(targetFor(1L).request())).willThrow(new AiAgentUnavailableException("down", null));
        given(validationService.releaseAfterAgentDown(1L, 2)).willReturn(true);
        willThrow(new IllegalStateException("db hiccup")).given(validationService).release(List.of(2L));
        try {
            worker.run();
        } catch (IllegalStateException ignored) {
            // 예외는 스케줄러가 로그로 남긴다. 여기서는 쉬기가 걸렸는지만 본다.
        }

        clock.advance(Duration.ofSeconds(29));
        worker.run();

        verify(validationService, times(1)).claimMatched(anyInt());
    }
    @Test
    void AI_다운_뒤_되돌리기가_실패해도_실패로_남기지_않고_멈춰서_쉰다() {
        given(validationService.claimMatched(10)).willReturn(List.of(1L, 2L));
        given(validationService.prepareRequest(1L)).willReturn(targetFor(1L));
        given(client.validate(targetFor(1L).request())).willThrow(new AiAgentUnavailableException("down", null));
        willThrow(new IllegalStateException("db hiccup")).given(validationService).releaseAfterAgentDown(1L, 2);

        worker.run();
        clock.advance(Duration.ofSeconds(29));
        worker.run();

        verify(recorder, never()).recordFailure(any());
        verify(validationService, never()).prepareRequest(2L);
        verify(validationService, times(1)).claimMatched(anyInt());
    }
}
