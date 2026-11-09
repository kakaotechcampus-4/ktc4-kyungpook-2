package com.itda.backend.service.agent;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.itda.backend.dto.response.agent.AiProgressItem;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class AiProgressClient {

    private static final ParameterizedTypeReference<List<AiProgressItem>> ITEMS = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public AiProgressClient(@Qualifier("aiProgressRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public Optional<List<AiProgressItem>> fetch() {
        try {
            return Optional.ofNullable(restClient.get().uri("/progress").retrieve().body(ITEMS));
        } catch (RuntimeException e) {
            log.debug("ai progress fetch failed: {}", e.toString());
            return Optional.empty();
        }
    }
}
