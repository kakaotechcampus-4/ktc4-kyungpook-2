package com.itda.backend.service.agent;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.exception.agent.AiAgentException;
import com.itda.backend.exception.agent.AiAgentRejectedException;
import com.itda.backend.exception.agent.AiAgentUnavailableException;
import com.itda.backend.exception.agent.AiAgentUnreadableResponseException;

import lombok.extern.slf4j.Slf4j;

/**
 * AI 서버 호출 공통 부분. 에이전트별 클라이언트(매칭·검증·요약·인사이트)가 경로와 요청·응답 타입만 정해서 쓴다.
 *
 * <p>대량으로 돌리면 몇 건은 응답이 없다(9/28 실서버 11건 중 1건). 그래서 연결 실패·타임아웃·5xx 는
 * 설정된 간격으로 다시 시도한다. "잠시 뒤 다시"라는 408·429 도 같다. 그 밖의 4xx 는 요청 자체가 계약과 다르다는 뜻이라
 * 다시 보내도 같으므로 바로 실패한다.
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
        return post(path, request, "journalEntryId=" + journalEntryId);
    }

    /**
     * 처리 단위가 일지 한 건이 아닌 에이전트용. {@code context} 는 재시도 로그와 예외 메시지에만 쓴다.
     *
     * <p>인사이트는 아동 × 기간으로 부르느라 넘길 일지 id 가 없다. 그렇다고 {@code null} 을 넘기면 실패
     * 로그가 전부 {@code journalEntryId=null} 이 돼서 어느 호출이 터졌는지 알 수 없다.
     *
     * <p>⚠️ {@code context} 에 아이 이름 같은 개인정보를 넣지 않는다. ID 와 기간이면 충분하다.
     */
    public String post(String path, Object request, String context) {
        for (int attempt = 0; ; attempt++) {
            try {
                return restClient.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(String.class);
            } catch (RestClientException e) {
                if (!isTransient(e)) {
                    throw notRetried(e, path, context);
                }
                if (attempt >= retryBackoffs.size()) {
                    throw new AiAgentUnavailableException(
                            "agent call failed after retries path=" + path + " " + context, e);
                }
                log.warn("agent call failed, retrying path={} {} attempt={}",
                        path, context, attempt + 1, e);
                sleep(retryBackoffs.get(attempt));
            }
        }
    }

    // 연결 실패·타임아웃·5xx 와, 4xx 중 "잠시 뒤 다시"라는 뜻인 408·429 는 다시 보내면 될 수 있다.
    private static boolean isTransient(RestClientException e) {
        if (e instanceof ResourceAccessException || e instanceof HttpServerErrorException) {
            return true;
        }
        return e instanceof HttpClientErrorException clientError
                && (clientError.getStatusCode().value() == HttpStatus.REQUEST_TIMEOUT.value()
                        || clientError.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value());
    }

    // 그 밖의 4xx 는 BE 가 계약과 다른 요청을 보낸 것이다. 4xx 가 아닌데 실패했으면(모르는 상태 코드, 본문 읽기 실패)
    // 응답을 못 읽은 것이다.
    private static AiAgentException notRetried(RestClientException e, String path, Object context) {
        if (e instanceof HttpClientErrorException) {
            return new AiAgentRejectedException("agent rejected request path=" + path + " " + context, e);
        }
        return new AiAgentUnreadableResponseException(
                "agent response could not be read path=" + path + " " + context, e);
    }

    public <T> T parse(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            throw new AiAgentUnreadableResponseException("agent returned empty body");
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new AiAgentUnreadableResponseException("agent returned unreadable body", e);
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
