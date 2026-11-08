package com.itda.backend.service.agent;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;

import com.itda.backend.dto.response.agent.AiStatusResponse;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

/** 기관 화면용 AI 상태. 판정은 {@link AiAvailability} 가 하고 여기서는 기관 사용자인지만 본다. */
@Service
@RequiredArgsConstructor
public class AiStatusService {

    private final AiAvailability availability;
    private final UserService userService;
    private final Clock clock;

    public AiStatusResponse getStatus(String userId) {
        userService.getOrganizationIdOf(userId);
        AiAvailability.Status status = availability.status();
        OffsetDateTime since = status.since() == null ? null
                : status.since().atZone(clock.getZone()).toOffsetDateTime();
        return new AiStatusResponse(status.available(), since);
    }
}
