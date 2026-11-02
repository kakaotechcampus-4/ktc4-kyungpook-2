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

    // 그 밖의 4xx 는 BE 가 계약과 다른 요청을 보낸 것이다. 4xx 도 아닌데 실패했으면 요청 본문을 못 만드는 등 BE 쪽에서
    // 난 것이라 원인을 고르지 않고 그대로 올린다(INTERNAL_ERROR).
    private static AiAgentException notRetried(RestClientException e, String path, Object context) {
        if (e instanceof HttpClientErrorException) {
            return new AiAgentRejectedException("agent rejected request path=" + path + " " + context, e);
        }
        return new AiAgentException("agent call failed path=" + path + " " + context, e);
    }

    public <T> T parse(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            throw new AiAgentUnreadableResponseException("agent returned empty body");
        }
        T parsed;
        try {
            parsed = objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new AiAgentUnreadableResponseException("agent returned unreadable body", e);
        }
        // 본문이 JSON null 이면 예외 없이 null 이 나온다. 넘기면 받는 쪽에서 NPE 가 나 BE 오류로 잘못 분류된다.
        if (parsed == null) {
            throw new AiAgentUnreadableResponseException("agent returned null body");
        }
        return parsed;
    }

    /**
     * 재시도까지 실패한 마지막 응답 body 의 {@code detail.<field>}. 연결 실패·타임아웃이거나 body 를 읽을 수 없으면
     * {@code null} 이다. AI 는 503 body 에 {@code detail.reason}(BE 가 후속 처리를 가르는 값)과
     * {@code detail.failure}(실패 종류, 기록용)를 싣는다.
     */
    public String detailOf(AiAgentUnavailableException e, String field) {
        if (!(e.getCause() instanceof HttpServerErrorException serverError)) {
            return null;
        }
        try {
            return objectMapper.readTree(serverError.getResponseBodyAsString())
                    .path("detail").path(field).asText(null);
        } catch (JsonProcessingException ignored) {
            return null;
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
