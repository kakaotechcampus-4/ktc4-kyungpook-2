package com.itda.backend.service.notify;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.itda.backend.dto.request.notify.NotifyAgentRequest;
import com.itda.backend.dto.response.notify.NotifyAgentResponse;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.service.agent.AiAgentClient;
import com.itda.backend.service.agent.AiAgentProperties;

/**
 * 알림 에이전트({@code AI POST /notify}) 호출 (#419). 재시도·상태 확인은 {@link AiAgentClient} 가 한다.
 *
 * <p><b>503 이 두 종류다.</b> {@code detail.reason} 으로 갈린다 (AI/notify/CRITERIA.md §4).
 * <ul>
 *   <li>{@code llm_unavailable} — 메일 본문을 못 썼다. 메일은 안 나갔다</li>
 *   <li>{@code delivery_failed} — 본문은 썼는데 메일 서버가 받지 않았다</li>
 * </ul>
 * 둘 다 다시 하면 될 수 있어 처리는 같다. 나눠 적는 것은 <b>무엇을 고쳐야 하는지</b>가 달라서다 —
 * 앞은 모델, 뒤는 메일 설정이다. {@code insight_notification.failure_reason} 에 그대로 남긴다.
 *
 * <p>근거 미달({@code blocked})은 200 으로 온다. 예외가 아니다 — 재시도해도 같은 결과라 실패로 다루면
 * 영원히 다시 보낸다.
 */
@Component
public class NotifyAgentClient {

    private final AiAgentClient agentClient;

    @Autowired
    public NotifyAgentClient(@Qualifier("notifyAgentRestClient") RestClient restClient, ObjectMapper objectMapper,
            AiAgentProperties properties, NotifyProperties notifyProperties) {
        this(new AiAgentClient(restClient, objectMapper, properties, notifyProperties.timeoutRetries()));
    }

    NotifyAgentClient(AiAgentClient agentClient) {
        this.agentClient = agentClient;
    }

    /** 승인된 인사이트를 보호자 메일로 내보낸다. 이름은 로그 맥락에 넣지 않는다. */
    public NotifyAgentResponse send(NotifyAgentRequest request) {
        String context = "childId=" + request.childId()
                + " period=" + request.periodFrom() + ".." + request.periodTo()
                + " notificationId=" + request.notificationId();
        return agentClient.parse(agentClient.post("/notify", request, context), NotifyAgentResponse.class);
    }

    /**
     * 실패 사유 한 줄. {@code insight_notification.failure_reason} 에 남긴다.
     *
     * <p>인사이트와 같은 모양이다 — {@code delivery_failed} · {@code llm_unavailable:timeout} 처럼
     * {@code reason:failure} 로 붙인다. body 가 없거나 이 모양이 아니면 {@code agent_unreachable} 이다.
     */
    public String failureOf(RuntimeException e) {
        if (e instanceof AiAgentUnavailableException unavailable) {
            String reason = agentClient.detailOf(unavailable, "reason");
            if (reason != null) {
                String failure = agentClient.detailOf(unavailable, "failure");
                return failure == null ? reason : reason + ":" + failure;
            }
            return "agent_unreachable";
        }
        return "agent_error";
    }

    /** 집기 전에 AI 가 떠 있는지 본다. */
    public boolean isAvailable() {
        return agentClient.isAvailable();
    }
}
