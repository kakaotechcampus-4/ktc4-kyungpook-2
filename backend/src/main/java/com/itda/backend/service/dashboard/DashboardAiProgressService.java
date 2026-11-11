package com.itda.backend.service.dashboard;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.dto.response.dashboard.DashboardAiProgressResponse;
import com.itda.backend.service.agent.AiProgressProperties;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DashboardAiProgressService {

    private final UserService userService;
    private final AiProgressProperties properties;
    private final AiProgressStore store;

    @Transactional(readOnly = true)
    public DashboardAiProgressResponse getProgress(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        if (!properties.enabled()) {
            return DashboardAiProgressResponse.disabled();
        }
        store.markAsked();
        return new DashboardAiProgressResponse(true, store.read(organizationId));
    }
}
