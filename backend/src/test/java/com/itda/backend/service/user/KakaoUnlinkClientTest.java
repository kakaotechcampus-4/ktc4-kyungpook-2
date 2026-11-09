package com.itda.backend.service.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * 카카오 연결 해제 응답을 세 가지로 나누는지. 실제 HTTP 로 요청 모양(어드민 키 헤더 · 폼 본문)과 타임아웃까지 본다.
 */
class KakaoUnlinkClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 성공과_이미_끊긴_사용자는_끊긴_것이다() {
        assertThat(KakaoUnlinkClient.classify(HttpStatusCode.valueOf(200), null)).isEqualTo(KakaoUnlinkResult.UNLINKED);
        assertThat(KakaoUnlinkClient.classify(HttpStatusCode.valueOf(400), -101)).isEqualTo(KakaoUnlinkResult.UNLINKED);
    }

    @Test
    void 카카오가_분명히_거절하면_실패다() {
        assertThat(KakaoUnlinkClient.classify(HttpStatusCode.valueOf(401), -401)).isEqualTo(KakaoUnlinkResult.FAILED);
        assertThat(KakaoUnlinkClient.classify(HttpStatusCode.valueOf(403), -3)).isEqualTo(KakaoUnlinkResult.FAILED);
        assertThat(KakaoUnlinkClient.classify(HttpStatusCode.valueOf(400), -2)).isEqualTo(KakaoUnlinkResult.FAILED);
        assertThat(KakaoUnlinkClient.classify(HttpStatusCode.valueOf(400), null)).isEqualTo(KakaoUnlinkResult.FAILED);
    }

    @Test
    void 일시_오류는_결과를_모르는_것이다() {
        assertThat(KakaoUnlinkClient.classify(HttpStatusCode.valueOf(400), -1)).isEqualTo(KakaoUnlinkResult.UNKNOWN);
        assertThat(KakaoUnlinkClient.classify(HttpStatusCode.valueOf(400), -7)).isEqualTo(KakaoUnlinkResult.UNKNOWN);
        assertThat(KakaoUnlinkClient.classify(HttpStatusCode.valueOf(429), -10)).isEqualTo(KakaoUnlinkResult.UNKNOWN);
        assertThat(KakaoUnlinkClient.classify(HttpStatusCode.valueOf(400), -10)).isEqualTo(KakaoUnlinkResult.UNKNOWN);
        assertThat(KakaoUnlinkClient.classify(HttpStatusCode.valueOf(502), null)).isEqualTo(KakaoUnlinkResult.UNKNOWN);
    }

    @Test
    void 어드민_키와_회원번호를_폼으로_보내고_응답_코드를_읽는다() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        start(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 400, "{\"code\":-101,\"msg\":\"NotRegisteredUserException\"}");
        });

        KakaoUnlinkResult result = client("admin-key", Duration.ofSeconds(2)).unlink(123456789L);

        assertThat(result).isEqualTo(KakaoUnlinkResult.UNLINKED);
        assertThat(authorization.get()).isEqualTo("KakaoAK admin-key");
        assertThat(body.get()).contains("target_id_type=user_id").contains("target_id=123456789");
    }

    @Test
    void 키_오류는_실패다() throws IOException {
        start(exchange -> respond(exchange, 401, "{\"code\":-401,\"msg\":\"InvalidTokenException\"}"));

        assertThat(client("wrong", Duration.ofSeconds(2)).unlink(1L)).isEqualTo(KakaoUnlinkResult.FAILED);
    }

    @Test
    void 응답이_늦으면_결과를_모르는_것이다() throws IOException {
        start(exchange -> {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"id\":1}");
        });

        assertThat(client("admin-key", Duration.ofMillis(300)).unlink(1L)).isEqualTo(KakaoUnlinkResult.UNKNOWN);
    }

    @Test
    void 어드민_키가_없으면_부르지_않고_실패다() {
        assertThat(client("", Duration.ofSeconds(1)).unlink(1L)).isEqualTo(KakaoUnlinkResult.FAILED);
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/user/unlink", handler);
        server.start();
    }

    private KakaoUnlinkClient client(String adminKey, Duration readTimeout) {
        String baseUrl = server == null ? "http://127.0.0.1:9" : "http://127.0.0.1:" + server.getAddress().getPort();
        WithdrawalProperties properties = new WithdrawalProperties(Duration.ofSeconds(1), "local",
                new WithdrawalProperties.Kakao(baseUrl, adminKey, null, Duration.ofSeconds(1), readTimeout),
                new WithdrawalProperties.Worker(false, 60_000));
        return new KakaoUnlinkClient(RestClient.builder(), properties, new ObjectMapper());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
