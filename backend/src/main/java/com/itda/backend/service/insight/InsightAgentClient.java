package com.itda.backend.service.insight;

import org.springframework.stereotype.Component;

import com.itda.backend.dto.request.insight.InsightAgentRequest;
import com.itda.backend.dto.response.insight.InsightAgentResponse;
import com.itda.backend.service.agent.AiAgentClient;

import lombok.RequiredArgsConstructor;

/**
 * 인사이트 에이전트({@code AI POST /insight}) 호출. 재시도·상태 확인은 {@link AiAgentClient} 가 한다.
 *
 * <p><b>요약과 달리 전용 RestClient 를 두지 않는다.</b> 요약이 {@code summaryAgentRestClient} 를 따로 쓰는 건
 * LLM 을 90초까지 기다려서인데(AI/summary/config.py LUNA_TIMEOUT), 인사이트는 60초라
 * (AI/insight/config.py LUNA_TIMEOUT) 매칭·검증과 같은 공통 {@code app.ai.read-timeout} 으로 충분하다.
 * LLM 호출도 한 번뿐이다 — {@code perceive → plan → act → reflect} 중 {@code act} 하나다.
 *
 * <p>⚠️ 다만 70초라는 값은 <b>일지 한 건을 처리하는 에이전트에 맞춰 정한 것을 물려받은 것</b>이고 인사이트로
 * 재 본 적은 없다. 인사이트는 기간치 claim 을 한 번에 다루므로 LLM 밖 처리 시간이 claim 수에 따라 늘어난다.
 * 운영에서 타임아웃 뒤 재시도가 보이면 {@code app.summary.read-timeout} 처럼 전용 값을 두는 게 다음 수다.
 *
 * <p>AI 는 LLM 을 못 쓰면 503 을 준다. 5xx 라서 재시도하고, 그래도 실패하면
 * {@code AiAgentUnavailableException} 이 올라간다. 요약처럼 {@code detail.reason} 으로 갈라야 하는
 * 하위 예외가 인사이트에는 없다 — {@code LlmUnavailable} 하나뿐이다 (AI/insight/graph.py).
 */
@Component
@RequiredArgsConstructor
public class InsightAgentClient {

    private final AiAgentClient agentClient;

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

    public boolean isAvailable() {
        return agentClient.isAvailable();
    }
}
