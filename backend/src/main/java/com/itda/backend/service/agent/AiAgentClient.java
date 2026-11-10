package com.itda.backend.service.agent;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
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

@Slf4j
@Component
public class AiAgentClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final List<Duration> retryBackoffs;
    private final int timeoutRetries;

    @Autowired
    public AiAgentClient(
            @Qualifier("aiAgentRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            AiAgentProperties properties) {
        this(restClient, objectMapper, properties, properties.retryBackoffs().size());
    }

    public AiAgentClient(RestClient restClient, ObjectMapper objectMapper, AiAgentProperties properties,
            int timeoutRetries) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.retryBackoffs = properties.retryBackoffs();
        this.timeoutRetries = timeoutRetries;
    }

    public String post(String path, Object request, Long journalEntryId) {
        return post(path, request, "journalEntryId=" + journalEntryId);
    }

    public String post(String path, Object request, String context) {
        int timeouts = 0;
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
                if (isReadTimeout(e)) {
                    timeouts++;
                }
                if (attempt >= retryBackoffs.size() || timeouts > timeoutRetries) {
                    throw new AiAgentUnavailableException(
                            "agent call failed after retries path=" + path + " " + context, e);
                }
                log.warn("agent call failed, retrying path={} {} attempt={}",
                        path, context, attempt + 1, e);
                sleep(retryBackoffs.get(attempt));
            }
        }
    }

    private static boolean isTransient(RestClientException e) {
        if (e instanceof ResourceAccessException || e instanceof HttpServerErrorException) {
            return true;
        }
        return e instanceof HttpClientErrorException clientError
                && (clientError.getStatusCode().value() == HttpStatus.REQUEST_TIMEOUT.value()
                        || clientError.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value());
    }

    private static boolean isReadTimeout(RestClientException e) {
        return e instanceof ResourceAccessException
                && e.getCause() instanceof SocketTimeoutException
                && !(e.getCause() instanceof ConnectTimeoutException);
    }

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
        if (parsed == null) {
            throw new AiAgentUnreadableResponseException("agent returned null body");
        }
        return parsed;
    }

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
            Thread.currentThread().interrupt();
            throw new AiAgentException("interrupted while waiting to retry", e);
        }
    }
}
