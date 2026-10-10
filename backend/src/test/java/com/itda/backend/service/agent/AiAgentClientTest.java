package com.itda.backend.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.exception.AiAgentException;
import com.itda.backend.exception.AiAgentUnavailableException;

class AiAgentClientTest {

    private static final String AI = "http://ai:8000";
    private static final String BODY = "{\"journal_entry_id\": 1041, \"verdict\": \"PASS\"}";

    private MockRestServiceServer server;
    private AiAgentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(AI);
        server = MockRestServiceServer.bindTo(builder).build();
        // 백오프는 설정값이다. 테스트에서는 기다리지 않도록 0으로 둔다.
        AiAgentProperties properties = new AiAgentProperties(AI, Duration.ofSeconds(3), Duration.ofSeconds(65),
                List.of(Duration.ZERO, Duration.ZERO));
        client = new AiAgentClient(builder.build(), new ObjectMapper(), properties);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Reply(String verdict) {
    }

    @Test
    void 요청을_JSON으로_보내고_응답_원문을_돌려준다() {
        server.expect(requestTo(AI + "/validation"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"journal_entry_id\": 1041}", true))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        String body = client.post("/validation", Map.of("journal_entry_id", 1041), 1041L);

        assertThat(body).isEqualTo(BODY);
        server.verify();
    }

    @Test
    void 서버_오류는_두_번까지_다시_시도한다() {
        server.expect(requestTo(AI + "/validation")).andRespond(withServerError());
        server.expect(requestTo(AI + "/validation")).andRespond(withServerError());
        server.expect(requestTo(AI + "/validation")).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(client.post("/validation", Map.of(), 1041L)).isEqualTo(BODY);
        server.verify();
    }

    @Test
    void 응답이_없으면_다시_시도한다() {
        server.expect(requestTo(AI + "/validation")).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });
        server.expect(requestTo(AI + "/validation")).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(client.post("/validation", Map.of(), 1041L)).isEqualTo(BODY);
        server.verify();
    }

    @Test
    void 세_번_모두_실패하면_AI를_쓸_수_없다는_예외를_던진다() {
        server.expect(requestTo(AI + "/validation")).andRespond(withServerError());
        server.expect(requestTo(AI + "/validation")).andRespond(withServerError());
        server.expect(requestTo(AI + "/validation")).andRespond(withServerError());

        // AI 가 응답하지 못하는 상태라는 뜻 — 워커는 이 예외를 보고 이번 차례를 멈춘다.
        assertThatThrownBy(() -> client.post("/validation", Map.of(), 1041L))
                .isInstanceOf(AiAgentUnavailableException.class);
        server.verify();
    }

    @Test
    void 요청이_잘못됐다는_응답은_다시_시도하지_않는다() {
        server.expect(requestTo(AI + "/validation")).andRespond(withBadRequest());

        assertThatThrownBy(() -> client.post("/validation", Map.of(), 1041L))
                .isInstanceOf(AiAgentException.class)
                .isNotInstanceOf(AiAgentUnavailableException.class);
        server.verify();
    }

    @Test
    void 재시도를_기다리다_인터럽트되면_표시를_되살리고_예외를_던진다() {
        AiAgentProperties slow = new AiAgentProperties(AI, Duration.ofSeconds(3), Duration.ofSeconds(65),
                List.of(Duration.ofMinutes(1)));
        RestClient.Builder builder = RestClient.builder().baseUrl(AI);
        MockRestServiceServer slowServer = MockRestServiceServer.bindTo(builder).build();
        AiAgentClient slowClient = new AiAgentClient(builder.build(), new ObjectMapper(), slow);
        slowServer.expect(requestTo(AI + "/validation")).andRespond(withServerError());

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> slowClient.post("/validation", Map.of(), 1041L))
                    .isInstanceOf(AiAgentException.class)
                    .isNotInstanceOf(AiAgentUnavailableException.class);
            // 워커는 이 표시를 보고 종료 중이라 판단해 실패로 남기지 않는다.
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // 다른 테스트에 영향이 없도록 인터럽트 표시를 지운다
        }
    }

    @Test
    void 응답을_원하는_타입으로_읽는다() {
        Reply reply = client.parse(BODY, Reply.class);

        assertThat(reply.verdict()).isEqualTo("PASS");
    }

    @Test
    void 응답_본문이_비었거나_JSON이_아니면_예외를_던진다() {
        assertThatThrownBy(() -> client.parse("", Reply.class)).isInstanceOf(AiAgentException.class);
        assertThatThrownBy(() -> client.parse(null, Reply.class)).isInstanceOf(AiAgentException.class);
        assertThatThrownBy(() -> client.parse("<html>", Reply.class)).isInstanceOf(AiAgentException.class);
    }

    @Test
    void AI_상태_확인이_성공하면_사용_가능하다() {
        server.expect(requestTo(AI + "/health")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\": \"ok\"}", MediaType.APPLICATION_JSON));

        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    void AI_상태_확인이_실패하면_사용할_수_없다() {
        server.expect(requestTo(AI + "/health")).andRespond(withServerError());
        server.expect(requestTo(AI + "/health")).andRespond(request -> {
            throw new ConnectException("Connection refused");
        });

        assertThat(client.isAvailable()).isFalse();
        assertThat(client.isAvailable()).isFalse();
    }
}
