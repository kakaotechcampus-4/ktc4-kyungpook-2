package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
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

import com.itda.backend.domain.insight.InsightRun;
import com.itda.backend.dto.request.insight.InsightAgentRequest;
import com.itda.backend.dto.response.insight.InsightAgentResponse;
import com.itda.backend.exception.agent.AiAgentException;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.repository.summary.ApprovedEntryDay;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.service.agent.WorkerProperties;

@ExtendWith(MockitoExtension.class)
class InsightWorkerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final InsightProperties PROPERTIES =
            new InsightProperties(new WorkerProperties(true, 5000, 10), 7, SEOUL, 14, false, List.of());

    @Mock
    private InsightService insightService;
    @Mock
    private InsightAgentClient client;
    @Mock
    private InsightResultRecorder recorder;
    @Mock
    private ChildContextRepository childContextRepository;

    private InsightWorker worker;

    @BeforeEach
    void setUp() {
        worker = new InsightWorker(insightService, client, recorder, childContextRepository, PROPERTIES);
        // 기본은 "아직 안 다룬 기간". 다룬 기간을 보는 테스트가 따로 덮어쓴다.
        lenient().when(recorder.needsRun(anyLong(), any(), any())).thenReturn(true);
    }

    private static LocalDate periodTo() {
        return LocalDate.now(SEOUL).with(DayOfWeek.SUNDAY).minusWeeks(1);
    }

    private InsightRun run(Long childId) {
        return InsightRun.claim(childId, periodTo().minusDays(6), periodTo(), 1);
    }

    private PreparedInsight request(Long childId) {
        return new PreparedInsight(new InsightAgentRequest(childId, "김준호", "2026-10-01", "2026-10-07", List.of(), List.of()), 41L);
    }

    private InsightAgentReply reply() {
        return new InsightAgentReply(new InsightAgentResponse(8L, null, null, List.of(), true), "{}");
    }

    private void agentUp() {
        given(client.isAvailable()).willReturn(true);
    }

    /** 지난주 수요일 기록이 최근에 승인된 아이들. */
    private void candidates(Long... childIds) {
        approved(java.util.Arrays.stream(childIds)
                .map(id -> new ApprovedEntryDay(id, periodTo().minusDays(4)))
                .toArray(ApprovedEntryDay[]::new));
    }

    private void approved(ApprovedEntryDay... days) {
        given(childContextRepository.findApprovedEntryDaysSince(any())).willReturn(List.of(days));
    }

    /** needsRun 이 받은 기간들. 워커가 어느 (아이, 기간)을 봤는지 이것으로 확인한다. */
    private List<LocalDate[]> periodsAsked() {
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(recorder, org.mockito.Mockito.atLeast(0)).needsRun(anyLong(), from.capture(), to.capture());
        List<LocalDate[]> asked = new java.util.ArrayList<>();
        for (int i = 0; i < from.getAllValues().size(); i++) {
            asked.add(new LocalDate[] {from.getAllValues().get(i), to.getAllValues().get(i)});
        }
        return asked;
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
    void 기간은_관찰_날짜가_속한_주의_월요일부터_일요일까지다() {
        agentUp();
        approved(new ApprovedEntryDay(8L, periodTo().minusDays(4)));

        worker.run();

        assertThat(periodsAsked()).singleElement().satisfies(period -> {
            assertThat(period[0]).isEqualTo(periodTo().minusDays(6));
            assertThat(period[0].getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(period[1]).isEqualTo(periodTo());
        });
    }

    /** 밀린 기록을 늦게 승인해도 그 주를 다시 본다 (#239). "지난주" 만 보면 영영 재료가 안 된다. */
    @Test
    void 늦게_승인된_옛_주도_본다() {
        agentUp();
        LocalDate threeWeeksAgoSunday = periodTo().minusWeeks(2);
        approved(new ApprovedEntryDay(8L, threeWeeksAgoSunday.minusDays(2)));

        worker.run();

        assertThat(periodsAsked()).singleElement()
                .satisfies(period -> assertThat(period[1]).isEqualTo(threeWeeksAgoSunday));
    }

    /** 같은 주에 승인된 요약이 여럿이어도 그 주는 한 번만 본다. */
    @Test
    void 같은_주의_요약이_여럿이어도_한_번만_본다() {
        agentUp();
        approved(new ApprovedEntryDay(8L, periodTo().minusDays(6)),
                new ApprovedEntryDay(8L, periodTo().minusDays(1)),
                new ApprovedEntryDay(8L, periodTo()));

        worker.run();

        assertThat(periodsAsked()).hasSize(1);
    }

    /** 끝나지 않은 이번 주는 기본으로 뺀다 — 승인이 들어올 때마다 새 회차가 생겨 Gate 2 에 비슷한 글이 쌓인다. */
    @Test
    void 이번_주_기록은_기본으로_보지_않는다() {
        agentUp();
        approved(new ApprovedEntryDay(8L, LocalDate.now(SEOUL)));

        worker.run();

        assertThat(periodsAsked()).isEmpty();
    }

    /** 시연에서는 그날 올려 승인한 기록이 바로 인사이트가 돼야 한다 (include-current-week). */
    @Test
    void 설정을_켜면_이번_주_기록도_본다() {
        worker = new InsightWorker(insightService, client, recorder, childContextRepository,
                new InsightProperties(new WorkerProperties(true, 5000, 10), 7, SEOUL, 14, true, List.of()));
        agentUp();
        approved(new ApprovedEntryDay(8L, LocalDate.now(SEOUL)));

        worker.run();

        assertThat(periodsAsked()).singleElement()
                .satisfies(period -> assertThat(period[1]).isEqualTo(periodTo().plusWeeks(1)));
    }

    /** 승인 시각으로 고른다. 몇 일 전 승인까지 볼지는 설정값이다. */
    @Test
    void 최근_승인만_조회한다() {
        agentUp();
        approved();

        worker.run();

        ArgumentCaptor<java.time.LocalDateTime> since = ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        verify(childContextRepository).findApprovedEntryDaysSince(since.capture());
        assertThat(since.getValue()).isBetween(java.time.LocalDateTime.now().minusDays(14).minusMinutes(1),
                java.time.LocalDateTime.now().minusDays(14).plusMinutes(1));
    }

    /** 앱이 죽어 RUNNING 으로 남은 실행은 되돌려야 다시 집힌다. 켜진 뒤 한 번만 한다. */
    @Test
    void 켜진_뒤_첫_차례에만_멈춘_실행을_되돌린다() {
        agentUp();
        approved();

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
        verify(recorder).record(any(), any(), eq("{}"), eq(41L), anyInt());
    }

    /** 이미 끝난 기간이다. 재료를 모으는 일이 쿼리 몇 번이라 그 전에 걸러야 한다. */
    @Test
    void 이미_다룬_기간은_재료도_모으지_않는다() {
        agentUp();
        candidates(8L);
        given(recorder.needsRun(eq(8L), any(), any())).willReturn(false);

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
        verify(recorder, never()).record(any(), any(), any(), any(), anyInt());
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

        verify(recorder).recordFailure(any(), any());
        verify(recorder).record(any(), any(), any(), any(), anyInt());
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
        AiAgentUnavailableException unavailable = new AiAgentUnavailableException("503", null);
        willThrow(unavailable).given(client).analyze(any());
        given(client.failureOf(unavailable)).willReturn("llm_unavailable:empty_response");

        worker.run();

        // 왜 실패했는지 남긴다 (#266)
        verify(recorder).recordFailure(any(), eq("llm_unavailable:empty_response"));
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
        verify(recorder).record(any(), any(), any(), any(), anyInt());
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
        verify(recorder, never()).recordFailure(any(), any());
    }

    @Test
    void AI가_꺼져_있으면_아무도_집지_않는다() {
        given(client.isAvailable()).willReturn(false);

        worker.run();

        verify(childContextRepository, never()).findApprovedEntryDaysSince(any());
        verify(recorder, never()).claim(anyLong(), any(), any());
    }

    @Test
    void 한_차례에_batch_size_만큼만_집는다() {
        worker = new InsightWorker(insightService, client, recorder, childContextRepository,
                new InsightProperties(new WorkerProperties(true, 5000, 2), 7, SEOUL, 14, false, List.of()));
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
        worker = new InsightWorker(insightService, client, recorder, childContextRepository,
                new InsightProperties(new WorkerProperties(true, 5000, 1), 7, SEOUL, 14, false, List.of()));
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
                .withBean(ChildContextRepository.class, () -> mock(ChildContextRepository.class))
                .withBean(InsightProperties.class, () -> PROPERTIES)
                .withUserConfiguration(InsightWorker.class)
                .withPropertyValues("app.insight.worker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(InsightWorker.class));
    }
}
