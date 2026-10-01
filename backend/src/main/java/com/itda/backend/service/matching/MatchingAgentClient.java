package com.itda.backend.service.matching;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.dto.request.MatchingAgentRequest;
import com.itda.backend.dto.response.MatchingAgentResponse;
import com.itda.backend.exception.MatchingAgentException;
import com.itda.backend.exception.MatchingAgentUnavailableException;

import lombok.extern.slf4j.Slf4j;

/**
 * 매칭 에이전트({@code AI POST /matching}) 호출.
 *
 * <p>대량으로 돌리면 몇 건은 응답이 없다(9/28 실서버 11건 중 1건). 그래서 연결 실패·타임아웃·5xx 는
 * 설정된 간격으로 다시 시도한다. 4xx 는 요청 자체가 계약과 다르다는 뜻이라 다시 보내도 같으므로 바로 실패한다.
 */
@Slf4j
@Component
public class MatchingAgentClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final List<Duration> retryBackoffs;

    public MatchingAgentClient(
            @Qualifier("matchingAgentRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            MatchingProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.retryBackoffs = properties.retryBackoffs();
    }

    public MatchingAgentReply match(MatchingAgentRequest request) {
        for (int attempt = 0; ; attempt++) {
            try {
                String body = restClient.post()
                        .uri("/matching")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(String.class);
                return new MatchingAgentReply(parse(body), body);
            } catch (ResourceAccessException | HttpServerErrorException e) {
                if (attempt >= retryBackoffs.size()) {
                    throw new MatchingAgentUnavailableException(
                            "matching agent call failed after retries journalEntryId=" + request.journalEntryId(), e);
                }
                log.warn("matching agent call failed, retrying journalEntryId={} attempt={}",
                        request.journalEntryId(), attempt + 1, e);
                sleep(retryBackoffs.get(attempt));
            } catch (RestClientException e) {
                throw new MatchingAgentException(
                        "matching agent rejected request journalEntryId=" + request.journalEntryId(), e);
            }
        }
    }

    /** 일지를 집기 전에 AI 가 떠 있는지 본다. 꺼져 있을 때 일지를 집으면 전부 실패로 남기 때문이다. */
    public boolean isAvailable() {
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    private MatchingAgentResponse parse(String body) {
        if (body == null || body.isBlank()) {
            throw new MatchingAgentException("matching agent returned empty body");
        }
        try {
            return objectMapper.readValue(body, MatchingAgentResponse.class);
        } catch (JsonProcessingException e) {
            throw new MatchingAgentException("matching agent returned unreadable body", e);
        }
    }

    private void sleep(Duration backoff) {
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            // 앱 종료 중이다. 인터럽트 표시를 되살려 두면 워커가 보고 실패로 남기지 않고 멈춘다.
            Thread.currentThread().interrupt();
            throw new MatchingAgentException("interrupted while waiting to retry", e);
        }
    }
}
