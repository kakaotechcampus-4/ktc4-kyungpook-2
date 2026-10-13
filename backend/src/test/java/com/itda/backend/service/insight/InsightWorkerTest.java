package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.DayOfWeek;
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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.UnexpectedRollbackException;

import com.itda.backend.domain.InsightRun;
import com.itda.backend.dto.request.InsightAgentRequest;
import com.itda.backend.dto.response.InsightAgentResponse;
import com.itda.backend.exception.AiAgentException;
import com.itda.backend.exception.AiAgentUnavailableException;
import com.itda.backend.repository.SummaryResultRepository;
import com.itda.backend.service.agent.WorkerProperties;

@ExtendWith(MockitoExtension.class)
class InsightWorkerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final InsightProperties PROPERTIES =
            new InsightProperties(new WorkerProperties(true, 5000, 10), 7, SEOUL);

    @Mock
    private InsightService insightService;
    @Mock
    private InsightAgentClient client;
    @Mock
    private InsightResultRecorder recorder;
    @Mock
    private SummaryResultRepository summaryResultRepository;

    private InsightWorker worker;

    @BeforeEach
    void setUp() {
        worker = new InsightWorker(insightService, client, recorder, summaryResultRepository, PROPERTIES);
    }

    private static LocalDate periodTo() {
        return LocalDate.now(SEOUL).with(DayOfWeek.SUNDAY).minusWeeks(1);
    }

    private InsightRun run(Long childId) {
        return InsightRun.claim(childId, periodTo().minusDays(6), periodTo(), 1);
    }

    private InsightAgentRequest request(Long childId) {
        return new InsightAgentRequest(childId, "김준호", "2026-10-01", "2026-10-07", List.of(), List.of());
    }

    private InsightAgentReply reply() {
        return new InsightAgentReply(new InsightAgentResponse(8L, null, null, List.of(), true), "{}");
    }

    private void agentUp() {
        given(client.isAvailable()).willReturn(true);
    }

    private void candidates(Long... childIds) {
        given(summaryResultRepository.findChildIdsWithSummaryBetween(any(), any())).willReturn(List.of(childIds));
    }

    /** 재료가 있고 아직 안 다룬 아이. */
    private void ready(Long childId) {
        given(insightService.prepare(eq(childId), any(), any())).willReturn(Optional.of(request(childId)));
        given(recorder.claim(eq(childId), any(), any())).willReturn(Optional.of(run(childId)));
    }

    /**
     * 기간의 끝이 고정돼 있어야 집기가 중복을 막는다. "어제까지 7일" 이면 자정마다 키가 밀려서
     * 같은 아이가 6일 겹치는 창으로 매일 다시 돌고 Gate 2 대기 목록에 비슷한 글이 쌓인다.
     */
    @Test
    void 기간은_지난_주_일요일에서_끊는다() {
        agentUp();
        given(summaryResultRepository.findChildIdsWithSummaryBetween(any(), any())).willReturn(List.of());

        worker.run();

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(summaryResultRepository).findChildIdsWithSummaryBetween(from.capture(), to.capture());

        assertThat(to.getValue().getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(to.getValue()).isBefore(LocalDate.now(SEOUL));
        assertThat(from.getValue()).isEqualTo(to.getValue().minusDays(6));
    }

    /** 앱이 죽어 RUNNING 으로 남은 실행은 되돌려야 다시 집힌다. 켜진 뒤 한 번만 한다. */
    @Test
    void 켜진_뒤_첫_차례에만_멈춘_실행을_되돌린다() {
        agentUp();
        given(summaryResultRepository.findChildIdsWithSummaryBetween(any(), any())).willReturn(List.of());

        worker.run();
        worker.run();

        verify(recorder, times(1)).releaseStuck();
    }

    @Test
    void 집은_아이를_호출하고_결과를_남긴다() {
        agentUp();
        candidates(8L);
        ready(8L);
        given(client.analyze(any())).willReturn(reply());

        worker.run();

        verify(client).analyze(any());
        verify(recorder).record(any(), any(), eq("{}"));
    }

    /** 이미 끝난 기간이다. 재료를 모으는 일이 쿼리 몇 번이라 그 전에 걸러야 한다. */
    @Test
    void 이미_다룬_기간은_재료도_모으지_않는다() {
        agentUp();
        candidates(8L);
        given(recorder.alreadyHandled(eq(8L), any(), any())).willReturn(true);

        worker.run();

        verify(insightService, never()).prepare(anyLong(), any(), any());
        verify(recorder, never()).claim(anyLong(), any(), any());
        verify(client, never()).analyze(any());
    }

    /**
     * 승인된 재료가 없거나 동의가 없는 아이다. <b>집지 않는다</b> — 집어서 DONE 으로 남기면,
     * 그 뒤에 교사가 그 기간의 요약을 승인해도 다시 보지 않는다.
     * "낼 것이 없었다" 와 "아직 묻지 않았다" 는 다르다.
     */
    @Test
    void 재료가_없으면_집지도_않는다() {
        agentUp();
        candidates(8L);
        given(insightService.prepare(eq(8L), any(), any())).willReturn(Optional.empty());

        worker.run();

        verify(recorder, never()).claim(anyLong(), any(), any());
        verify(recorder, never()).record(any(), any(), any());
        verify(client, never()).analyze(any());
    }

    /** 4xx·응답 해석 실패는 다시 보내도 같다. 그 아이만 실패로 남기고 다음으로 간다. */
    @Test
    void 계약_오류는_그_아이만_실패로_남기고_계속한다() {
        agentUp();
        candidates(8L, 9L);
        ready(8L);
        ready(9L);
        given(client.analyze(any()))
                .willThrow(new AiAgentException("422"))
                .willReturn(reply());

        worker.run();

        verify(recorder).recordFailure(any());
        verify(recorder).record(any(), any(), any());
        verify(client, times(2)).analyze(any());
    }

    /**
     * 재시도까지 응답이 없으면 지금 아이를 포함해 멈춘다. {@code /health} 는 LLM 이 살아 있는지까지
     * 보지 않아서, LLM 장애가 이어지면 아이마다 실패로 끝난다 (요약 워커와 같은 이유).
     */
    @Test
    void AI가_응답하지_않으면_남은_아이를_건드리지_않는다() {
        agentUp();
        candidates(8L, 9L);
        ready(8L);
        willThrow(new AiAgentUnavailableException("503", null)).given(client).analyze(any());

        worker.run();

        verify(recorder).recordFailure(any());
        verify(insightService, never()).prepare(eq(9L), any(), any());
        verify(client, times(1)).analyze(any());
    }

    /**
     * 집기는 제약 위반을 트랜잭션 밖으로 던진다 — 안에서 잡아도 커밋 시점에 프록시가 다시 던지기 때문이다.
     * 워커가 그걸 잡지 않으면 {@code @Scheduled} 메서드가 차례 중간에 스택트레이스로 끊긴다.
     */
    @Test
    void 집기_경합에_지면_조용히_건너뛴다() {
        agentUp();
        candidates(8L, 9L);
        given(insightService.prepare(anyLong(), any(), any()))
                .willReturn(Optional.of(request(8L)), Optional.of(request(9L)));
        given(recorder.claim(eq(8L), any(), any()))
                .willThrow(new UnexpectedRollbackException("rollback-only"));
        given(recorder.claim(eq(9L), any(), any())).willReturn(Optional.of(run(9L)));
        given(client.analyze(any())).willReturn(reply());

        worker.run();

        // 진 쪽은 건너뛰고 다음 아이로 이어간다
        verify(client, times(1)).analyze(any());
        verify(recorder).record(any(), any(), any());
    }

    @Test
    void 제약_위반으로_져도_건너뛴다() {
        agentUp();
        candidates(8L);
        given(insightService.prepare(eq(8L), any(), any())).willReturn(Optional.of(request(8L)));
        given(recorder.claim(eq(8L), any(), any()))
                .willThrow(new DataIntegrityViolationException("unique"));

        worker.run();

        verify(client, never()).analyze(any());
        verify(recorder, never()).recordFailure(any());
    }

    @Test
    void AI가_꺼져_있으면_아무도_집지_않는다() {
        given(client.isAvailable()).willReturn(false);

        worker.run();

        verify(summaryResultRepository, never()).findChildIdsWithSummaryBetween(any(), any());
        verify(recorder, never()).claim(anyLong(), any(), any());
    }

    @Test
    void 한_차례에_batch_size_만큼만_집는다() {
        worker = new InsightWorker(insightService, client, recorder, summaryResultRepository,
                new InsightProperties(new WorkerProperties(true, 5000, 2), 7, SEOUL));
        agentUp();
        candidates(8L, 9L, 10L);
        ready(8L);
        ready(9L);
        given(client.analyze(any())).willReturn(reply());

        worker.run();

        verify(recorder, times(2)).claim(anyLong(), any(), any());
        verify(recorder, never()).claim(eq(10L), any(), any());
    }

    /** 집지 못한 아이는 처리 수에 넣지 않는다 — 안 그러면 경합에 진 만큼 그 차례가 비어 버린다. */
    @Test
    void 집지_못한_아이는_처리_수에_넣지_않는다() {
        worker = new InsightWorker(insightService, client, recorder, summaryResultRepository,
                new InsightProperties(new WorkerProperties(true, 5000, 1), 7, SEOUL));
        agentUp();
        candidates(8L, 9L);
        given(insightService.prepare(anyLong(), any(), any()))
                .willReturn(Optional.of(request(8L)), Optional.of(request(9L)));
        given(recorder.claim(eq(8L), any(), any())).willReturn(Optional.empty());
        given(recorder.claim(eq(9L), any(), any())).willReturn(Optional.of(run(9L)));
        given(client.analyze(any())).willReturn(reply());

        worker.run();

        verify(recorder).claim(eq(9L), any(), any());
        verify(client, times(1)).analyze(any());
    }

    @Test
    void 설정으로_워커를_끌_수_있다() {
        new ApplicationContextRunner()
                .withBean(InsightService.class, () -> mock(InsightService.class))
                .withBean(InsightAgentClient.class, () -> mock(InsightAgentClient.class))
                .withBean(InsightResultRecorder.class, () -> mock(InsightResultRecorder.class))
                .withBean(SummaryResultRepository.class, () -> mock(SummaryResultRepository.class))
                .withBean(InsightProperties.class, () -> PROPERTIES)
                .withUserConfiguration(InsightWorker.class)
                .withPropertyValues("app.insight.worker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(InsightWorker.class));
    }
}
