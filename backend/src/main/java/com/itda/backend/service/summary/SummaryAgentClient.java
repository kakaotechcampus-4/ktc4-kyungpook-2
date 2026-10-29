package com.itda.backend.service.summary;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.dto.request.summary.SummaryAgentRequest;
import com.itda.backend.dto.response.summary.SummaryAgentResponse;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.exception.summary.SummaryNoGroundedClaimsException;
import com.itda.backend.service.agent.AiAgentClient;
import com.itda.backend.service.agent.AiAgentProperties;

/**
 * 요약 에이전트({@code AI POST /summary}) 호출. 재시도·상태 확인은 {@link AiAgentClient} 가 한다.
 *
 * <p>요약은 LLM 을 90초까지 기다려서(AI/summary/config.py LUNA_TIMEOUT) 매칭·검증용 응답 대기 시간(70초)으로는
 * 느린 정상 응답을 먼저 끊고 다시 부르게 된다. 그래서 응답 대기 시간이 긴 RestClient 를 따로 쓴다.
 *
 * <p>AI 는 두 경우에 503 을 주고 {@code detail.reason} 으로 나눈다. 둘 다 5xx 라 재시도한다.
 * <ul>
 * <li>{@code llm_unavailable}: LLM 을 못 쓴다. 재시도까지 실패하면 워커가 이번 차례를 멈춘다
 * <li>{@code no_grounded_claims}: 모델이 쓴 문장이 근거를 못 찾아 모두 버려졌다. 모델이 부를 때마다 다른 문장을 쓰므로
 * 재시도하면 될 수 있다. 재시도까지 같으면 {@link SummaryNoGroundedClaimsException} 으로 그 묶음만 요약 실패로 남긴다
 * </ul>
 * 마지막 응답을 기준으로 한다. 사유를 읽을 수 없으면 LLM 장애로 본다.
 */
@Component
public class SummaryAgentClient {

    private static final String NO_GROUNDED_CLAIMS = "no_grounded_claims";

    private final AiAgentClient agentClient;

    @Autowired
    public SummaryAgentClient(@Qualifier("summaryAgentRestClient") RestClient restClient, ObjectMapper objectMapper,
            AiAgentProperties properties) {
        this(new AiAgentClient(restClient, objectMapper, properties));
    }

    SummaryAgentClient(AiAgentClient agentClient) {
        this.agentClient = agentClient;
    }

    public SummaryAgentReply summarize(SummaryAgentRequest request) {
        // 일지 한 건이 아니라 묶음이라 로그에는 첫 일지 id 를 남긴다.
        Long firstEntryId = request.sources().isEmpty() ? null : request.sources().get(0).journalEntryId();
        String body;
        try {
            body = agentClient.post("/summary", request, firstEntryId);
        } catch (AiAgentUnavailableException e) {
            if (NO_GROUNDED_CLAIMS.equals(agentClient.detailOf(e, "reason"))) {
                throw new SummaryNoGroundedClaimsException(
                        "summary has no grounded claims after retries journalEntryId=" + firstEntryId, e);
            }
            throw e;
        }
        return new SummaryAgentReply(agentClient.parse(body, SummaryAgentResponse.class), body);
    }

    public boolean isAvailable() {
        return agentClient.isAvailable();
    }
}
