package com.itda.backend.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.itda.backend.dto.response.agent.AiStatusResponse;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.fixture.MutableClock;
import com.itda.backend.service.user.UserService;

@ExtendWith(MockitoExtension.class)
class AiStatusServiceTest {

    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 11, 6, 10, 0));
    private final AiAvailability availability = new AiAvailability(clock);

    @Mock
    private UserService userService;

    private AiStatusService service;

    @BeforeEach
    void setUp() {
        service = new AiStatusService(availability, userService, clock);
    }

    @Test
    void AI가_살아_있으면_사용_가능하고_시각이_없다() {
        given(userService.getOrganizationIdOf("teacher")).willReturn(1L);

        assertThat(service.getStatus("teacher")).isEqualTo(new AiStatusResponse(true, null));
    }

    @Test
    void AI가_멈췄으면_멈춘_시각을_서울_시간으로_보여준다() {
        given(userService.getOrganizationIdOf("teacher")).willReturn(1L);
        availability.reportCallGaveUp();
        clock.advance(Duration.ofSeconds(10));

        assertThat(service.getStatus("teacher")).isEqualTo(new AiStatusResponse(false,
                OffsetDateTime.of(2026, 11, 6, 10, 0, 0, 0, ZoneOffset.ofHours(9))));
    }

    @Test
    void 기관_사용자가_아니면_막는다() {
        given(userService.getOrganizationIdOf("parent"))
                .willThrow(new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED));

        assertThatThrownBy(() -> service.getStatus("parent")).isInstanceOf(UserException.class);
    }
}
