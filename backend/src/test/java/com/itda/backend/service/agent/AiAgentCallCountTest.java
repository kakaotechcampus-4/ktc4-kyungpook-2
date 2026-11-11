package com.itda.backend.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.service.insight.InsightAgentClientConfig;
import com.itda.backend.service.insight.InsightProperties;
import com.itda.backend.service.summary.SummaryAgentClientConfig;
import com.itda.backend.service.summary.SummaryProperties;
import com.sun.net.httpserver.HttpServer;

class AiAgentCallCountTest {

    private static final List<Duration> BACKOFFS = List.of(Duration.ZERO, Duration.ZERO);

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger calls = new AtomicInteger();
    private AiAgentProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
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
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        properties = new AiAgentProperties(baseUrl, Duration.ofSeconds(3), Duration.ofSeconds(10), BACKOFFS);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.shutdownNow();
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
                Duration.ofSeconds(10), false);
        RestClient restClient = new SummaryAgentClientConfig()
                .summaryAgentRestClient(RestClient.builder(), properties, summaryProperties);
        AiAgentClient client = new AiAgentClient(restClient, new ObjectMapper(), properties);

        assertThatThrownBy(() -> client.post("/summary", Map.of(), 1L))
                .isInstanceOf(AiAgentUnavailableException.class);

        assertThat(calls.get()).isEqualTo(BACKOFFS.size() + 1);
    }

    @Test
    void 인사이트용_클라이언트는_응답이_늦으면_한_번만_다시_부른다() {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        InsightProperties insightProperties = new InsightProperties(new WorkerProperties(true, 5000, 10), 7,
                ZoneId.of("Asia/Seoul"), 14, false, List.of(), Duration.ofMillis(200), 1);
        RestClient restClient = new InsightAgentClientConfig()
                .insightAgentRestClient(RestClient.builder(), properties, insightProperties);
        AiAgentClient client = new AiAgentClient(restClient, new ObjectMapper(), properties,
                insightProperties.timeoutRetries());

        assertThatThrownBy(() -> client.post("/insight", Map.of(), "childId=8"))
                .isInstanceOf(AiAgentUnavailableException.class);

        assertThat(calls.get()).isEqualTo(2);
    }
}
