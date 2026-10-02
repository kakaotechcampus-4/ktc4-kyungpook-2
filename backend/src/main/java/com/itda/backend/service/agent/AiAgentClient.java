package com.itda.backend.service.agent;

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
import com.itda.backend.exception.AiAgentException;
import com.itda.backend.exception.AiAgentUnavailableException;

import lombok.extern.slf4j.Slf4j;

/**
 * AI 서버 호출 공통 부분. 에이전트별 클라이언트(매칭·검증)가 경로와 요청·응답 타입만 정해서 쓴다.
 *
 * <p>대량으로 돌리면 몇 건은 응답이 없다(9/28 실서버 11건 중 1건). 그래서 연결 실패·타임아웃·5xx 는
 * 설정된 간격으로 다시 시도한다. 4xx 는 요청 자체가 계약과 다르다는 뜻이라 다시 보내도 같으므로 바로 실패한다.
 */
@Slf4j
@Component
public class AiAgentClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final List<Duration> retryBackoffs;

    public AiAgentClient(
            @Qualifier("aiAgentRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            AiAgentProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.retryBackoffs = properties.retryBackoffs();
    }

    /** {@code path} 로 요청을 보내고 응답 원문을 돌려준다. 원문은 결과 테이블의 raw_response 에 그대로 남긴다. */
    public String post(String path, Object request, Long journalEntryId) {
        for (int attempt = 0; ; attempt++) {
            try {
                return restClient.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(String.class);
            } catch (ResourceAccessException | HttpServerErrorException e) {
                if (attempt >= retryBackoffs.size()) {
                    throw new AiAgentUnavailableException(
                            "agent call failed after retries path=" + path + " journalEntryId=" + journalEntryId, e);
                }
                log.warn("agent call failed, retrying path={} journalEntryId={} attempt={}",
                        path, journalEntryId, attempt + 1, e);
                sleep(retryBackoffs.get(attempt));
            } catch (RestClientException e) {
                throw new AiAgentException(
                        "agent rejected request path=" + path + " journalEntryId=" + journalEntryId, e);
            }
        }
    }

    public <T> T parse(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            throw new AiAgentException("agent returned empty body");
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new AiAgentException("agent returned unreadable body", e);
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

    private void sleep(Duration backoff) {
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            // 앱 종료 중이다. 인터럽트 표시를 되살려 두면 워커가 보고 실패로 남기지 않고 멈춘다.
            Thread.currentThread().interrupt();
            throw new AiAgentException("interrupted while waiting to retry", e);
        }
    }
}
