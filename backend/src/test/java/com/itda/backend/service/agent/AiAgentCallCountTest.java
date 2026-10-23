package com.itda.backend.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.service.summary.SummaryAgentClientConfig;
import com.itda.backend.service.summary.SummaryProperties;
import com.sun.net.httpserver.HttpServer;

/**
 * AI 서버가 실제로 몇 번 불리는지 센다 (#153).
 *
 * <p>{@code MockRestServiceServer} 는 요청 팩토리를 가짜로 바꿔 끼워서 HTTP 라이브러리가 몰래 하는 재시도를
 * 보지 못한다. 그래서 여기서는 진짜 HTTP 서버를 띄우고, 운영과 같은 설정 클래스로 만든 RestClient 로 부른다.
 */
class AiAgentCallCountTest {

    private static final List<Duration> BACKOFFS = List.of(Duration.ZERO, Duration.ZERO);

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private AiAgentProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // LLM 이 죽었을 때 AI 가 주는 응답 그대로 — 503 + detail.reason
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"detail\":{\"reason\":\"llm_unavailable\",\"message\":\"Luna 호출 실패\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        properties = new AiAgentProperties(baseUrl, Duration.ofSeconds(3), Duration.ofSeconds(10), BACKOFFS);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 매칭_검증용_클라이언트는_설정된_재시도_횟수만큼만_부른다() {
        RestClient restClient = new AiAgentClientConfig().aiAgentRestClient(RestClient.builder(), properties);
        AiAgentClient client = new AiAgentClient(restClient, new ObjectMapper(), properties);

        assertThatThrownBy(() -> client.post("/validation", Map.of(), 1L))
                .isInstanceOf(AiAgentUnavailableException.class);

        assertThat(calls.get()).isEqualTo(BACKOFFS.size() + 1);
    }

    @Test
    void 요약용_클라이언트도_설정된_재시도_횟수만큼만_부른다() {
        SummaryProperties summaryProperties = new SummaryProperties(new WorkerProperties(true, 5000, 10),
                LocalTime.of(3, 0), Duration.ofMinutes(30), ZoneId.of("Asia/Seoul"), Duration.ofSeconds(10));
        RestClient restClient = new SummaryAgentClientConfig()
                .summaryAgentRestClient(RestClient.builder(), properties, summaryProperties);
        AiAgentClient client = new AiAgentClient(restClient, new ObjectMapper(), properties);

        assertThatThrownBy(() -> client.post("/summary", Map.of(), 1L))
                .isInstanceOf(AiAgentUnavailableException.class);

        assertThat(calls.get()).isEqualTo(BACKOFFS.size() + 1);
    }
}
