package com.itda.backend.service.dashboard;

import org.springframework.stereotype.Service;

import com.itda.backend.dto.response.dashboard.DashboardAiProgressResponse;
import com.itda.backend.service.agent.AiProgressProperties;
import com.itda.backend.service.user.UserService;

/**
 * 대시보드 진행 현황의 AI 진행 단계 (#453, api-spec O-83). 화면이 1초마다 부른다.
 *
 * <p>진행 현황을 다시 계산하지 않고 {@link AiProgressPoller} 가 채운 보관함에서 우리 기관 것만 꺼낸다. DB 는 기관
 * 사용자인지 보는 사용자 한 줄만 읽는다 ({@code /system/ai-status} 와 같다). 그래서 트랜잭션을 열지 않는다.
 * 불릴 때마다 적어 두면 폴러가 그걸 보고 AI 에 묻는다 — 아무도 안 보면 묻지 않는다.
 */
@Service
public class DashboardAiProgressService {

    private final UserService userService;
    private final AiProgressProperties properties;
    private final AiProgressStore store;

    public DashboardAiProgressService(UserService userService, AiProgressProperties properties,
            AiProgressStore store) {
        this.userService = userService;
        this.properties = properties;
        this.store = store;
    }

    public DashboardAiProgressResponse getProgress(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        if (!properties.enabled()) {
            return DashboardAiProgressResponse.disabled();
        }
        store.markAsked();
        return new DashboardAiProgressResponse(true, store.read(organizationId));
    }
}
