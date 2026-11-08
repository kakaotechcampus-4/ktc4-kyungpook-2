package com.itda.backend.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.itda.backend.dto.response.dashboard.DashboardAiProgressResponse;
import com.itda.backend.dto.response.dashboard.DashboardAiProgressResponse.Item;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Kind;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.service.agent.AiProgressProperties;
import com.itda.backend.service.user.UserService;

class DashboardAiProgressServiceTest {

    private static final Item OURS = new Item(Kind.ENTRY, "7", LocalDate.of(2026, 11, 8), null, "summary", "write", 12);
    private static final Item THEIRS = new Item(Kind.ENTRY, null, LocalDate.of(2026, 11, 9), null, "matching",
            "extract", 1);

    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 11, 9, 10, 0));
    private final AiProgressStore store = new AiProgressStore(clock);
    private final UserService userService = mock(UserService.class);

    private DashboardAiProgressService serviceWith(boolean enabled) {
        return new DashboardAiProgressService(userService,
                new AiProgressProperties(enabled, Duration.ofSeconds(1), Duration.ofMillis(500)), store);
    }

    @Test
    void 우리_기관_것만_돌려주고_폴러가_묻도록_적는다() {
        given(userService.getOrganizationIdOf("u1")).willReturn(3L);
        store.replace(Map.of(3L, List.of(OURS), 5L, List.of(THEIRS)));

        DashboardAiProgressResponse response = serviceWith(true).getProgress("u1");

        assertThat(response.enabled()).isTrue();
        assertThat(response.items()).containsExactly(OURS);
        assertThat(store.askedRecently()).isTrue();
    }

    @Test
    void 꺼져_있으면_빈_목록이고_폴러도_깨우지_않는다() {
        given(userService.getOrganizationIdOf("u1")).willReturn(3L);
        store.replace(Map.of(3L, List.of(OURS)));

        DashboardAiProgressResponse response = serviceWith(false).getProgress("u1");

        assertThat(response).isEqualTo(DashboardAiProgressResponse.disabled());
        assertThat(store.askedRecently()).isFalse();
    }

    @Test
    void 기관_사용자가_아니면_막는다() {
        given(userService.getOrganizationIdOf("parent"))
                .willThrow(new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED));

        assertThatThrownBy(() -> serviceWith(true).getProgress("parent")).isInstanceOf(UserException.class);
        assertThat(store.askedRecently()).isFalse();
    }
}
