package com.itda.backend.service.agent;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.itda.backend.dto.response.agent.AiProgressItem;

import lombok.extern.slf4j.Slf4j;

/**
 * AI 에게 에이전트가 지금 어느 단계인지 묻는다 (#452 {@code GET /progress}, #453).
 *
 * <p>재시도하지 않고 예외도 내지 않는다. 실패는 {@link Optional#empty()} 로, 정상인데 도는 일이 없으면 빈 목록으로
 * 돌려준다 — 부르는 쪽이 "잠깐 못 물었다"와 "지금 아무것도 안 돈다"를 구별해야 옛 문구를 얼마나 둘지 정할 수 있다.
 */
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
