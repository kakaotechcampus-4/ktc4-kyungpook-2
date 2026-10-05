package com.itda.backend.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.MatchingStatus;
import com.itda.backend.domain.MultiReason;
import com.itda.backend.dto.request.MatchingAgentRequest;
import com.itda.backend.dto.response.MatchingAgentResponse;
import com.itda.backend.exception.AiAgentException;
import com.itda.backend.service.agent.AiAgentClient;
import com.itda.backend.service.agent.AiAgentProperties;

class MatchingAgentClientTest {

    private static final String AI = "http://ai:8000";

    private static final String MULTI_RESPONSE = """
            {"journal_entry_id": 1041, "status": "multi", "matched_child_id": null, "confidence": 0.62,
             "hint_mismatch": false, "evidence": [{"start": 12, "end": 15}], "mentioned_child_ids": [8, 9],
             "multi_reason": "co_mention", "candidates": [{"child_id": 8, "confidence": 0.62}, {"child_id": 9, "confidence": 0.6}],
             "llm_called": true, "added_later": "모르는 필드"}
            """;

    private MockRestServiceServer server;
    private MatchingAgentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(AI);
        server = MockRestServiceServer.bindTo(builder).build();
        // 재시도·상태 확인은 AiAgentClientTest 가 본다. 여기서는 매칭 계약(요청·응답 모양)만 본다.
        AiAgentProperties properties = new AiAgentProperties(AI, Duration.ofSeconds(3), Duration.ofSeconds(65),
                List.of());
        client = new MatchingAgentClient(new AiAgentClient(builder.build(), new ObjectMapper(), properties));
    }

    private MatchingAgentRequest request() {
        return new MatchingAgentRequest(1041L, "자유놀이 중 블록을 높이 쌓았다.",
                List.of(new MatchingAgentRequest.RosterEntry(8L, "박서연", "2019-05-05")),
                81L, "2026-08-21", "박서연", "2019-05-05");
    }

    @Test
    void 요청은_AI_계약대로_snake_case_JSON으로_보낸다() {
        server.expect(requestTo(AI + "/matching"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"journal_entry_id": 1041, "content": "자유놀이 중 블록을 높이 쌓았다.",
                         "roster": [{"child_id": 8, "name": "박서연", "birthdate": "2019-05-05"}],
                         "raw_record_id": 81, "entry_date": "2026-08-21",
                         "hint_name": "박서연", "hint_birthdate": "2019-05-05"}
                        """, true))
                .andRespond(withSuccess(MULTI_RESPONSE, MediaType.APPLICATION_JSON));

        client.match(request());

        server.verify();
    }

    @Test
    void 응답을_읽고_원문도_그대로_돌려준다() {
        server.expect(requestTo(AI + "/matching")).andRespond(withSuccess(MULTI_RESPONSE, MediaType.APPLICATION_JSON));

        MatchingAgentReply reply = client.match(request());

        MatchingAgentResponse response = reply.response();
        assertThat(response.journalEntryId()).isEqualTo(1041L);
        assertThat(response.status()).isEqualTo(MatchingStatus.MULTI);
        assertThat(response.matchedChildId()).isNull();
        assertThat(response.confidence()).isEqualTo(0.62);
        assertThat(response.multiReason()).isEqualTo(MultiReason.CO_MENTION);
        assertThat(response.mentionedChildIds().toString()).isEqualTo("[8,9]");
        assertThat(response.candidates().toString()).isEqualTo(
                "[{\"child_id\":8,\"confidence\":0.62},{\"child_id\":9,\"confidence\":0.6}]");
        assertThat(response.evidence().toString()).isEqualTo("[{\"start\":12,\"end\":15}]");
        assertThat(reply.rawJson()).isEqualTo(MULTI_RESPONSE);
    }

    @Test
    void AI_상태_확인이_성공하면_사용_가능하다() {
        server.expect(requestTo(AI + "/health")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\": \"ok\"}", MediaType.APPLICATION_JSON));

        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    void 응답_본문이_JSON이_아니면_예외를_던진다() {
        server.expect(requestTo(AI + "/matching")).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.match(request())).isInstanceOf(AiAgentException.class);
    }
}
