package com.itda.backend.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.itda.backend.dto.response.agent.AiProgressItem;

class AiProgressClientTest {

    private static final String AI = "http://ai:8000";

    private MockRestServiceServer server;
    private AiProgressClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(AI);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiProgressClient(builder.build());
    }

    @Test
    void AI_진행_목록을_읽는다() {
        server.expect(requestTo(AI + "/progress")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"agent": "matching", "journal_entry_id": 120, "node": "llm_judge", "elapsed_sec": 7},
                          {"agent": "summary", "child_id": 7, "entry_date": "2026-11-08", "institution_id": 3,
                           "node": "write", "elapsed_sec": 12},
                          {"agent": "insight", "child_id": 7, "period_from": "2026-11-03", "period_to": "2026-11-09",
                           "node": "find_claims", "elapsed_sec": 31}
                        ]""", MediaType.APPLICATION_JSON));

        Optional<List<AiProgressItem>> items = client.fetch();

        assertThat(items).hasValueSatisfying(list -> assertThat(list).containsExactly(
                new AiProgressItem("matching", 120L, null, null, null, null, "llm_judge", 7),
                new AiProgressItem("summary", null, 7L, LocalDate.of(2026, 11, 8), 3L, null, "write", 12),
                new AiProgressItem("insight", null, 7L, null, null, LocalDate.of(2026, 11, 3), "find_claims", 31)));
        server.verify();
    }

    @Test
    void 아무것도_안_돌면_빈_목록이다() {
        server.expect(requestTo(AI + "/progress")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.fetch()).hasValue(List.of());
    }

    @Test
    void 서버_오류면_실패로_돌려준다() {
        server.expect(requestTo(AI + "/progress")).andRespond(withServerError());

        assertThat(client.fetch()).isEmpty();
    }

    @Test
    void 시간_초과면_실패로_돌려준다() {
        server.expect(requestTo(AI + "/progress")).andRespond(withException(new SocketTimeoutException()));

        assertThat(client.fetch()).isEmpty();
    }

    @Test
    void 응답을_읽을_수_없으면_실패로_돌려준다() {
        server.expect(requestTo(AI + "/progress")).andRespond(withSuccess("{not json", MediaType.APPLICATION_JSON));

        assertThat(client.fetch()).isEmpty();
    }

    @Test
    void 본문이_없으면_실패로_돌려준다() {
        server.expect(requestTo(AI + "/progress")).andRespond(withSuccess());

        assertThat(client.fetch()).isEmpty();
    }
}
