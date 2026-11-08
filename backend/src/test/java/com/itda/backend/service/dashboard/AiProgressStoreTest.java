package com.itda.backend.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.itda.backend.dto.response.dashboard.DashboardAiProgressResponse.Item;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Kind;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.service.agent.AiProgressProperties;

class AiProgressStoreTest {

    private static final Item OURS = new Item(Kind.ENTRY, "7", LocalDate.of(2026, 11, 8), null, "summary", "write", 12);
    private static final Item THEIRS = new Item(Kind.ENTRY, null, LocalDate.of(2026, 11, 9), null, "matching",
            "extract", 1);

    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 11, 9, 10, 0));
    private final AiProgressStore store = new AiProgressStore(clock, everySeconds(1));

    private static AiProgressProperties everySeconds(long seconds) {
        return new AiProgressProperties(true, Duration.ofSeconds(seconds), Duration.ofMillis(500));
    }

    @Test
    void 처음에는_비어_있다() {
        assertThat(store.read(3L)).isEmpty();
    }

    @Test
    void 우리_기관_것만_읽는다() {
        store.replace(Map.of(3L, List.of(OURS), 5L, List.of(THEIRS)));

        assertThat(store.read(3L)).containsExactly(OURS);
        assertThat(store.read(5L)).containsExactly(THEIRS);
        assertThat(store.read(9L)).isEmpty();
    }

    @Test
    void 새로_받으면_통째로_바꾼다() {
        store.replace(Map.of(3L, List.of(OURS)));
        store.replace(Map.of(5L, List.of(THEIRS)));

        assertThat(store.read(3L)).isEmpty();
    }

    @Test
    void 마지막으로_받은_지_3초가_넘으면_비운다() {
        store.replace(Map.of(3L, List.of(OURS)));

        clock.advance(Duration.ofSeconds(3));
        assertThat(store.read(3L)).containsExactly(OURS);

        clock.advance(Duration.ofMillis(1));
        assertThat(store.read(3L)).isEmpty();
    }

    @Test
    void 묻는_간격을_늘리면_비우는_기준도_간격의_3배로_늘어난다() {
        AiProgressStore slow = new AiProgressStore(clock, everySeconds(5));
        slow.replace(Map.of(3L, List.of(OURS)));

        clock.advance(Duration.ofSeconds(15));
        assertThat(slow.read(3L)).containsExactly(OURS);

        clock.advance(Duration.ofMillis(1));
        assertThat(slow.read(3L)).isEmpty();
    }

    @Test
    void 최근_5초_안에_누가_물었는지_기억한다() {
        assertThat(store.askedRecently()).isFalse();

        store.markAsked();
        clock.advance(Duration.ofSeconds(5));
        assertThat(store.askedRecently()).isTrue();

        clock.advance(Duration.ofMillis(1));
        assertThat(store.askedRecently()).isFalse();
    }
}
