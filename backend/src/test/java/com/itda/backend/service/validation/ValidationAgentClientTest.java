package com.itda.backend.service.validation;

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
import com.itda.backend.domain.ValidationVerdict;
import com.itda.backend.dto.request.ValidationAgentRequest;
import com.itda.backend.dto.response.ValidationAgentResponse;
import com.itda.backend.exception.AiAgentException;
import com.itda.backend.exception.AiAgentUnavailableException;
import com.itda.backend.service.agent.AiAgentClient;
import com.itda.backend.service.agent.AiAgentProperties;

class ValidationAgentClientTest {

    private static final String AI = "http://ai:8000";

    private static final String BLOCK_RESPONSE = """
            {"journal_entry_id": 1041, "verdict": "BLOCK", "issue_types": ["개인정보표현"],
             "evidence": [{"start": 23, "end": 36}], "added_later": "모르는 필드"}
            """;

    private MockRestServiceServer server;
    private ValidationAgentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(AI);
        server = MockRestServiceServer.bindTo(builder).build();
        // 재시도·상태 확인은 AiAgentClientTest 가 본다. 여기서는 검증 계약(요청·응답 모양)만 본다.
        AiAgentProperties properties = new AiAgentProperties(AI, Duration.ofSeconds(3), Duration.ofSeconds(65),
                List.of());
        client = new ValidationAgentClient(new AiAgentClient(builder.build(), new ObjectMapper(), properties));
    }

    private ValidationAgentRequest request() {
        return new ValidationAgentRequest(1041L, "임유진이 블록을 높이 쌓았다.", 8L, "임유진");
    }

    @Test
    void 요청은_AI_계약대로_snake_case_JSON으로_보낸다() {
        server.expect(requestTo(AI + "/validation"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"journal_entry_id": 1041, "content": "임유진이 블록을 높이 쌓았다.",
                         "subject_child_id": 8, "subject_name": "임유진"}
                        """, true))
                .andRespond(withSuccess(BLOCK_RESPONSE, MediaType.APPLICATION_JSON));

        client.validate(request());

        server.verify();
    }

    @Test
    void 응답을_읽고_원문도_그대로_돌려준다() {
        server.expect(requestTo(AI + "/validation")).andRespond(withSuccess(BLOCK_RESPONSE, MediaType.APPLICATION_JSON));

        ValidationAgentReply reply = client.validate(request());

        ValidationAgentResponse response = reply.response();
        assertThat(response.journalEntryId()).isEqualTo(1041L);
        assertThat(response.verdict()).isEqualTo(ValidationVerdict.BLOCK);
        assertThat(response.issueTypes().toString()).isEqualTo("[\"개인정보표현\"]");
        assertThat(response.evidence().toString()).isEqualTo("[{\"start\":23,\"end\":36}]");
        assertThat(reply.rawJson()).isEqualTo(BLOCK_RESPONSE);
    }

    @Test
    void LLM을_못_써서_503이면_재시도_끝에_AI를_쓸_수_없다고_본다() {
        // AI 는 LLM 호출이 실패하면 503 을 준다 (#98). 일지 탓이 아니라 워커가 이번 차례를 멈춰야 한다.
        server.expect(requestTo(AI + "/validation")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\": {\"reason\": \"llm_unavailable\", \"message\": \"Luna 호출 실패\"}}"));

        assertThatThrownBy(() -> client.validate(request())).isInstanceOf(AiAgentUnavailableException.class);
    }

    @Test
    void 응답_본문이_JSON이_아니면_예외를_던진다() {
        server.expect(requestTo(AI + "/validation")).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.validate(request())).isInstanceOf(AiAgentException.class);
    }

    @Test
    void AI_상태_확인을_넘겨준다() {
        server.expect(requestTo(AI + "/health")).andRespond(withSuccess("{\"status\": \"ok\"}", MediaType.APPLICATION_JSON));

        assertThat(client.isAvailable()).isTrue();
    }
}
