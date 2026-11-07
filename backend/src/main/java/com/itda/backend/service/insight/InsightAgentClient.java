package com.itda.backend.service.insight;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.itda.backend.dto.request.insight.InsightAgentRequest;
import com.itda.backend.dto.response.insight.InsightAgentResponse;
import com.itda.backend.exception.agent.AiAgentRejectedException;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.exception.agent.AiAgentUnreadableResponseException;
import com.itda.backend.service.agent.AiAgentClient;
import com.itda.backend.service.agent.AiAgentProperties;

/**
 * 인사이트 에이전트({@code AI POST /insight}) 호출. 재시도·상태 확인은 {@link AiAgentClient} 가 한다.
 *
 * <p><b>전용 RestClient 를 쓴다 (#386).</b> 수정 루프(AI #362)와 도구 사용(AI #373)을 켜면 요청 하나에서 LLM 을
 * 여러 번 부른다. 공통 값(70초)이면 느린 정상 응답을 끊고 다시 부르기만 반복한다. AI 가 요청 하나를
 * 180초(INSIGHT_DEADLINE, AI #373) 안에 끝내므로 응답 대기는 그보다 조금 긴 {@code app.insight.read-timeout},
 * 시간 초과 재시도는 {@code app.insight.timeout-retries} 번으로 줄인다. AI 에 호출이 더 붙어도 상한이 180초라
 * 이 값은 다시 바꾸지 않는다.
 *
 * <p>⚠️ 시간 초과로 다시 보내도 AI 는 앞 요청을 멈추지 않는다 — 그동안 같은 아이를 두 번 돌린다. 그래서 1번만 다시 보낸다.
 *
 * <p>AI 는 LLM 을 못 쓰면 503 을 준다. 5xx 라서 재시도하고, 그래도 실패하면
 * {@code AiAgentUnavailableException} 이 올라간다. 요약처럼 {@code detail.reason} 으로 갈라야 하는
 * 하위 예외가 인사이트에는 없다 — {@code LlmUnavailable} 하나뿐이다 (AI/insight/graph.py).
 */
@Component
public class InsightAgentClient {

    private final AiAgentClient agentClient;

    @Autowired
    public InsightAgentClient(@Qualifier("insightAgentRestClient") RestClient restClient, ObjectMapper objectMapper,
            AiAgentProperties properties, InsightProperties insightProperties) {
        this(new AiAgentClient(restClient, objectMapper, properties, insightProperties.timeoutRetries()));
    }

    InsightAgentClient(AiAgentClient agentClient) {
        this.agentClient = agentClient;
    }

    /**
     * 한 아이의 한 기간을 인사이트로 만든다.
     *
     * <p>{@code insights} 가 빈 응답도 정상이다 — 판단하지 않고 그대로 돌려준다. 억지로 만든 패턴이
     * 과잉해석이라 "낼 것이 없었다" 가 정답인 경우가 있다 (AI/insight/CRITERIA.md §3).
     */
    public InsightAgentReply analyze(InsightAgentRequest request) {
        // 일지 한 건이 아니라 아동 × 기간 단위라 로그 맥락도 그렇게 남긴다. 이름은 넣지 않는다.
        String context = "childId=" + request.childId()
                + " period=" + request.periodFrom() + ".." + request.periodTo();
        String body = agentClient.post("/insight", request, context);
        return new InsightAgentReply(agentClient.parse(body, InsightAgentResponse.class), body);
    }

    /**
     * 실패 사유 한 줄. {@code insight_run.failure_reason} 에 남긴다 (#266).
     *
     * <p>AI 가 503 body 에 실은 {@code detail.reason} 에 실패 종류 {@code detail.failure} 가 있으면
     * {@code llm_unavailable:empty_response} 처럼 붙인다 (AI {@code /metrics} 와 같은 모양, #264). body 가
     * 없거나 이 모양이 아니면(연결 실패·BE 쪽 시간 초과·프록시 오류 페이지) {@code agent_unreachable} 이다.
     */
    public String failureOf(RuntimeException e) {
        if (e instanceof AiAgentUnavailableException unavailable) {
            String reason = agentClient.detailOf(unavailable, "reason");
            if (reason == null) {
                return "agent_unreachable";
            }
            String failure = agentClient.detailOf(unavailable, "failure");
            return failure == null ? reason : reason + ":" + failure;
        }
        if (e instanceof AiAgentRejectedException) {
            return "agent_rejected";
        }
        if (e instanceof AiAgentUnreadableResponseException) {
            return "unreadable_response";
        }
        return "error";
    }

    public boolean isAvailable() {
        return agentClient.isAvailable();
    }
}
