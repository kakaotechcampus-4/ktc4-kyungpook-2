package com.itda.backend.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.itda.backend.dto.response.agent.AiProgressItem;
import com.itda.backend.dto.response.dashboard.DashboardAiProgressResponse.Item;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Kind;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.service.agent.AiProgressClient;
import com.itda.backend.service.agent.AiProgressProperties;

class AiProgressPollerTest {

    private static final AiProgressItem RAW = new AiProgressItem("summary", null, 7L, LocalDate.of(2026, 11, 8), 3L,
            null, "write", 12);
    private static final Item ROW = new Item(Kind.ENTRY, "7", LocalDate.of(2026, 11, 8), null, "summary", "write", 12);
    private static final AiProgressProperties ON = new AiProgressProperties(true, Duration.ofSeconds(1),
            Duration.ofMillis(500));

    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 11, 9, 10, 0));
    private final AiProgressStore store = new AiProgressStore(clock, ON);
    private final AiProgressClient client = mock(AiProgressClient.class);
    private final AiProgressTranslator translator = mock(AiProgressTranslator.class);
    private AiProgressPoller poller;

    @BeforeEach
    void setUp() {
        poller = new AiProgressPoller(ON, client, translator, store);
        given(translator.translate(List.of(RAW))).willReturn(Map.of(3L, List.of(ROW)));
    }

    @AfterEach
    void tearDown() {
        poller.stop();
    }

    @Test
    void 최근_5초_안에_아무도_묻지_않았으면_AI를_부르지_않는다() {
        poller.tick();

        verify(client, never()).fetch();
    }

    @Test
    void 누가_물었으면_AI에게_묻고_번역해서_담는다() {
        store.markAsked();
        given(client.fetch()).willReturn(Optional.of(List.of(RAW)));

        poller.tick();

        assertThat(store.read(3L)).containsExactly(ROW);
    }

    @Test
    void AI가_아무것도_안_돌린다고_하면_바로_비운다() {
        store.markAsked();
        given(client.fetch()).willReturn(Optional.of(List.of(RAW)), Optional.of(List.of()));
        given(translator.translate(List.of())).willReturn(Map.of());

        poller.tick();
        poller.tick();

        assertThat(store.read(3L)).isEmpty();
    }

    @Test
    void 잠깐_실패하면_그대로_두고_3초_넘게_실패하면_비운다() {
        store.markAsked();
        given(client.fetch()).willReturn(Optional.of(List.of(RAW)), Optional.empty());
        poller.tick();

        clock.advance(Duration.ofSeconds(2));
        poller.tick();
        assertThat(store.read(3L)).containsExactly(ROW);

        clock.advance(Duration.ofSeconds(2));
        poller.tick();
        assertThat(store.read(3L)).isEmpty();
    }

    @Test
    void 번역이_실패해도_실패로_보고_다음_차례를_이어_간다() {
        store.markAsked();
        given(client.fetch()).willReturn(Optional.of(List.of(RAW)));
        given(translator.translate(any())).willThrow(new IllegalStateException("db down"))
                .willReturn(Map.of(3L, List.of(ROW)));

        poller.tick();
        assertThat(store.read(3L)).isEmpty();

        poller.tick();
        assertThat(store.read(3L)).containsExactly(ROW);
    }

    @Test
    void Error가_나도_다음_차례를_이어_간다() {
        store.markAsked();
        given(client.fetch()).willReturn(Optional.of(List.of(RAW)));
        given(translator.translate(any())).willThrow(new StackOverflowError())
                .willReturn(Map.of(3L, List.of(ROW)));

        poller.tick();
        poller.tick();

        assertThat(store.read(3L)).containsExactly(ROW);
    }

    @Test
    void AI를_못_물은_것도_실패로_세고_성공해야_풀린다() {
        store.markAsked();
        given(client.fetch()).willReturn(Optional.empty(), Optional.of(List.of(RAW)));

        poller.tick();
        assertThat(poller.isFailing()).isTrue();

        poller.tick();
        assertThat(poller.isFailing()).isFalse();
    }

    @Test
    void 번역이_실패하면_실패로_센다() {
        store.markAsked();
        given(client.fetch()).willReturn(Optional.of(List.of(RAW)));
        given(translator.translate(any())).willThrow(new IllegalStateException("db down"));

        poller.tick();

        assertThat(poller.isFailing()).isTrue();
    }

    @Test
    void 켜져_있으면_전용_스레드_하나로_돈다() {
        poller.start();

        assertThat(poller.isRunning()).isTrue();
    }

    @Test
    void 꺼져_있으면_스레드를_띄우지_않는다() {
        AiProgressPoller off = new AiProgressPoller(new AiProgressProperties(false, Duration.ofSeconds(1),
                Duration.ofMillis(500)), client, translator, store);

        off.start();

        assertThat(off.isRunning()).isFalse();
        verify(client, never()).fetch();
    }
}
