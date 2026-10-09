package com.itda.backend.service.summary;

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
import com.itda.backend.dto.request.SummaryAgentRequest;
import com.itda.backend.dto.response.SummaryAgentResponse;
import com.itda.backend.exception.AiAgentException;
import com.itda.backend.exception.AiAgentUnavailableException;
import com.itda.backend.service.agent.AiAgentClient;
import com.itda.backend.service.agent.AiAgentProperties;

class SummaryAgentClientTest {

    private static final String AI = "http://ai:8000";

    private static final String RESPONSE = """
            {"child_id": 8, "entry_date": "2026-10-08", "institution_id": 3,
             "content": "블록 놀이에서 친구에게 양보했다.",
             "claims": [{"text": "블록 놀이에서 친구에게 양보했다.",
                         "evidence": [{"journal_entry_id": 1041, "quote": "친구에게 양보함",
                                       "span": {"start": 7, "end": 15}}]}],
             "covered_entry_ids": [1041], "uncovered_entry_ids": [1042], "llm_called": true,
             "added_later": "모르는 필드"}
            """;

    private MockRestServiceServer server;
    private SummaryAgentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(AI);
        server = MockRestServiceServer.bindTo(builder).build();
        // 재시도·상태 확인은 AiAgentClientTest 가 본다. 여기서는 요약 계약(요청·응답 모양)만 본다.
        AiAgentProperties properties = new AiAgentProperties(AI, Duration.ofSeconds(3), Duration.ofSeconds(120),
                List.of());
        client = new SummaryAgentClient(new AiAgentClient(builder.build(), new ObjectMapper(), properties));
    }

    private SummaryAgentRequest request() {
        return new SummaryAgentRequest(8L, "김준호", "2026-10-08", 3L, "햇살초등학교",
                List.of(new SummaryAgentRequest.Source(1041L, "블록 놀이에서 친구에게 양보함", "2026-10-08"),
                        new SummaryAgentRequest.Source(1042L, "특이사항 없음", "2026-10-08")),
                List.of("서준호"));
    }

    @Test
    void 요청은_AI_계약대로_snake_case_JSON으로_보낸다() {
        server.expect(requestTo(AI + "/summary"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"child_id": 8, "child_name": "김준호", "entry_date": "2026-10-08",
                         "institution_id": 3, "institution_name": "햇살초등학교",
                         "sources": [
                           {"journal_entry_id": 1041, "content": "블록 놀이에서 친구에게 양보함", "entry_date": "2026-10-08"},
                           {"journal_entry_id": 1042, "content": "특이사항 없음", "entry_date": "2026-10-08"}],
                         "other_child_names": ["서준호"]}
                        """, true))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        client.summarize(request());

        server.verify();
    }

    @Test
    void 응답을_읽고_원문도_그대로_돌려준다() {
        server.expect(requestTo(AI + "/summary")).andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        SummaryAgentReply reply = client.summarize(request());

        SummaryAgentResponse response = reply.response();
        assertThat(response.content()).isEqualTo("블록 놀이에서 친구에게 양보했다.");
        assertThat(response.claims().get(0).get("evidence").get(0).get("quote").asText()).isEqualTo("친구에게 양보함");
        assertThat(response.coveredEntryIds().toString()).isEqualTo("[1041]");
        assertThat(response.uncoveredEntryIds().toString()).isEqualTo("[1042]");
        assertThat(reply.rawJson()).isEqualTo(RESPONSE);
    }

    @Test
    void LLM을_못_써서_503이면_AI를_쓸_수_없는_것으로_본다() {
        server.expect(requestTo(AI + "/summary")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\": {\"reason\": \"llm_unavailable\"}}"));

        assertThatThrownBy(() -> client.summarize(request())).isInstanceOf(AiAgentUnavailableException.class);
    }

    @Test
    void 계약과_다른_요청이라_422면_바로_실패한다() {
        server.expect(requestTo(AI + "/summary")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> client.summarize(request()))
                .isInstanceOf(AiAgentException.class)
                .isNotInstanceOf(AiAgentUnavailableException.class);
    }
}
