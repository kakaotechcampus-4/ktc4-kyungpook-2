package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.dto.request.insight.InsightAgentRequest;
import com.itda.backend.dto.response.insight.InsightAgentResponse;
import com.itda.backend.exception.agent.AiAgentException;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.service.agent.AiAgentClient;
import com.itda.backend.service.agent.AiAgentProperties;

class InsightAgentClientTest {

    private static final String AI = "http://ai:8000";

    private static final String RESPONSE = """
            {"child_id": 8, "period_from": "2026-10-01", "period_to": "2026-10-07",
             "insights": [
               {"situation": "점심 줄에서 기다릴 때",
                "behavior": "기관에 따라 반응이 달랐다",
                "content": "학교에서는 차례를 기다렸고, 센터에서는 끼어들었다.",
                "supports": [{"action": "손을 잡고 30까지 셈", "result": "1분 내 진정됨",
                              "claim_ids": ["31-0"]}],
                "claim_ids": ["31-0", "34-1"],
                "source_entry_ids": [1041, 1042],
                "source_institution_ids": [3, 5],
                "institution_types": ["센터", "학교"],
                "cross_institution": true}],
             "llm_called": true,
             "added_later": "모르는 필드"}
            """;

    private MockRestServiceServer server;
    private InsightAgentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(AI);
        server = MockRestServiceServer.bindTo(builder).build();
        // 재시도·상태 확인은 AiAgentClientTest 가 본다. 여기서는 인사이트 계약(요청·응답 모양)만 본다.
        AiAgentProperties properties = new AiAgentProperties(AI, Duration.ofSeconds(3), Duration.ofSeconds(70),
                List.of());
        client = new InsightAgentClient(new AiAgentClient(builder.build(), new ObjectMapper(), properties));
    }

    private InsightAgentRequest request() {
        return new InsightAgentRequest(8L, "김준호", "2026-10-01", "2026-10-07",
                List.of(new InsightAgentRequest.Claim("31-0", "점심 줄에서 차례를 기다렸다.", "2026-10-02",
                                3L, "학교", InsightAgentRequest.ClaimSource.AI,
                                List.of(new InsightAgentRequest.Evidence(1041L, "줄에서 차례를 기다림"))),
                        // 교사가 Gate 1 에서 새로 쓴 문장. 일지가 없어 번호가 비고 quote 가 그 문장이다 (#199).
                        new InsightAgentRequest.Claim("34-1", "약 먹은 날 차분했다.", "2026-10-05",
                                5L, "센터", InsightAgentRequest.ClaimSource.TEACHER,
                                List.of(new InsightAgentRequest.Evidence(null, "약 먹은 날 차분했다.")))),
                List.of(new InsightAgentRequest.Institution(3L, "학교"),
                        new InsightAgentRequest.Institution(5L, "센터")));
    }

    @Test
    void 요청은_AI_계약대로_snake_case_JSON으로_보낸다() {
        server.expect(requestTo(AI + "/insight"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"child_id": 8, "child_name": "김준호",
                         "period_from": "2026-10-01", "period_to": "2026-10-07",
                         "claims": [
                           {"claim_id": "31-0", "text": "점심 줄에서 차례를 기다렸다.", "entry_date": "2026-10-02",
                            "institution_id": 3, "institution_type": "학교", "source": "AI",
                            "evidence": [{"journal_entry_id": 1041, "quote": "줄에서 차례를 기다림"}]},
                           {"claim_id": "34-1", "text": "약 먹은 날 차분했다.", "entry_date": "2026-10-05",
                            "institution_id": 5, "institution_type": "센터", "source": "TEACHER",
                            "evidence": [{"journal_entry_id": null, "quote": "약 먹은 날 차분했다."}]}],
                         "consented_institutions": [
                           {"institution_id": 3, "institution_type": "학교"},
                           {"institution_id": 5, "institution_type": "센터"}]}
                        """, true))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        client.analyze(request());

        server.verify();
    }

    @Test
    void 응답을_읽고_원문도_그대로_돌려준다() {
        server.expect(requestTo(AI + "/insight")).andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        InsightAgentReply reply = client.analyze(request());

        InsightAgentResponse response = reply.response();
        assertThat(response.childId()).isEqualTo(8L);
        assertThat(response.periodFrom()).isEqualTo("2026-10-01");
        assertThat(response.insights()).hasSize(1);

        InsightAgentResponse.Insight insight = response.insights().get(0);
        assertThat(insight.situation()).isEqualTo("점심 줄에서 기다릴 때");
        assertThat(insight.content()).isEqualTo("학교에서는 차례를 기다렸고, 센터에서는 끼어들었다.");
        assertThat(insight.crossInstitution()).isTrue();
        assertThat(insight.claimIds().toString()).isEqualTo("[\"31-0\",\"34-1\"]");
        assertThat(insight.sourceEntryIds().toString()).isEqualTo("[1041,1042]");
        assertThat(insight.sourceInstitutionIds().toString()).isEqualTo("[3,5]");
        assertThat(insight.supports().get(0).get("action").asText()).isEqualTo("손을 잡고 30까지 셈");

        assertThat(reply.rawJson()).isEqualTo(RESPONSE);
    }

    /** 억지로 만든 패턴이 과잉해석이라 "낼 것이 없었다" 가 정답인 경우가 있다 (CRITERIA §3). */
    @Test
    void 인사이트가_없는_응답도_정상으로_읽는다() {
        server.expect(requestTo(AI + "/insight")).andRespond(withSuccess("""
                {"child_id": 8, "period_from": "2026-10-01", "period_to": "2026-10-07",
                 "insights": [], "llm_called": true}
                """, MediaType.APPLICATION_JSON));

        InsightAgentReply reply = client.analyze(request());

        assertThat(reply.response().insights()).isEmpty();
        assertThat(reply.rawJson()).contains("\"insights\": []");
    }

    @Test
    void LLM을_못_써서_503이면_AI를_쓸_수_없는_것으로_본다() {
        server.expect(requestTo(AI + "/insight")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\": {\"reason\": \"llm_unavailable\"}}"));

        assertThatThrownBy(() -> client.analyze(request())).isInstanceOf(AiAgentUnavailableException.class);
    }

    @Test
    void 계약과_다른_요청이라_422면_바로_실패한다() {
        server.expect(requestTo(AI + "/insight")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> client.analyze(request()))
                .isInstanceOf(AiAgentException.class)
                .isNotInstanceOf(AiAgentUnavailableException.class);
    }

    /** 워커가 아이를 집기 전에 본다. 꺼져 있을 때 집으면 전부 실패로 남기 때문이다. */
    @Test
    void AI_상태_확인이_성공하면_사용_가능하다() {
        server.expect(requestTo(AI + "/health")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\": \"ok\"}", MediaType.APPLICATION_JSON));

        assertThat(client.isAvailable()).isTrue();
    }

    /**
     * 동의된 기관 조회가 비면 {@code null} 이 흘러들어올 수 있다. 그대로 보내면 AI 쪽 기본값이 아니라
     * {@code list[Institution]} 검증에 걸려 422 가 되고, 422 는 재시도 없이 바로 실패한다.
     */
    @Test
    void 목록이_null이면_빈_배열로_보낸다() {
        server.expect(requestTo(AI + "/insight"))
                .andExpect(content().json("""
                        {"child_id": 8, "claims": [], "consented_institutions": []}
                        """, false))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        client.analyze(new InsightAgentRequest(8L, "김준호", "2026-10-01", "2026-10-07", null, null));

        server.verify();
    }

    /** 일지 id 가 없는 호출이라, 실패 로그·예외에 아동과 기간이 남아야 어느 호출이 터졌는지 알 수 있다. */
    @Test
    void 실패하면_아동과_기간을_예외_메시지에_남긴다() {
        server.expect(requestTo(AI + "/insight")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.analyze(request()))
                .hasMessageContaining("childId=8")
                .hasMessageContaining("period=2026-10-01..2026-10-07")
                .hasMessageNotContaining("김준호");
    }
}
