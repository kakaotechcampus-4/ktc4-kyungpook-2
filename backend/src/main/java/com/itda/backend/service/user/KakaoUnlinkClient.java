package com.itda.backend.service.user;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.global.config.HttpClientRequestFactories;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class KakaoUnlinkClient {

    static final int NOT_REGISTERED_USER = -101;

    private static final Set<Integer> TRANSIENT_CODES = Set.of(-1, -7, -10);

    private final RestClient restClient;
    private final String adminKey;
    private final ObjectMapper objectMapper;

    public KakaoUnlinkClient(RestClient.Builder builder, WithdrawalProperties properties, ObjectMapper objectMapper) {
        WithdrawalProperties.Kakao kakao = properties.kakao();
        this.restClient = builder.clone()
                .baseUrl(kakao.baseUrl())
                .requestFactory(HttpClientRequestFactories.create(kakao.connectTimeout(), kakao.readTimeout()))
                .build();
        this.adminKey = kakao.adminKey();
        this.objectMapper = objectMapper;
    }

    public KakaoUnlinkResult unlink(long kakaoUserId) {
        if (adminKey == null || adminKey.isBlank()) {
            log.error("카카오 어드민 키(app.withdrawal.kakao.admin-key)가 없어 연결 해제를 부르지 않았습니다.");
            return KakaoUnlinkResult.FAILED;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("target_id_type", "user_id");
        form.add("target_id", String.valueOf(kakaoUserId));
        try {
            return restClient.post()
                    .uri("/v1/user/unlink")
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + adminKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, response) -> classify(response.getStatusCode(), readBody(response.getBody())));
        } catch (RestClientException e) {
            log.warn("카카오 연결 해제 응답을 받지 못했습니다: {}", e.getMessage());
            return KakaoUnlinkResult.UNKNOWN;
        }
    }

    record Body(boolean readable, Integer code) {

        static Body code(Integer code) {
            return new Body(true, code);
        }

        static Body unreadable() {
            return new Body(false, null);
        }
    }

    public KakaoLinkStatus linkStatus(long kakaoUserId) {
        if (adminKey == null || adminKey.isBlank()) {
            log.error("카카오 어드민 키(app.withdrawal.kakao.admin-key)가 없어 연결 상태를 묻지 않았습니다.");
            return KakaoLinkStatus.UNKNOWN;
        }
        try {
            return restClient.get()
                    .uri(uri -> uri.path("/v2/user/me")
                            .queryParam("target_id_type", "user_id")
                            .queryParam("target_id", kakaoUserId)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + adminKey)
                    .exchange((request, response) -> classifyLink(response.getStatusCode(), readBody(response.getBody())));
        } catch (RestClientException e) {
            log.warn("카카오 연결 상태 응답을 받지 못했습니다: {}", e.getMessage());
            return KakaoLinkStatus.UNKNOWN;
        }
    }

    static KakaoLinkStatus classifyLink(HttpStatusCode status, Body body) {
        if (status.is2xxSuccessful()) {
            return KakaoLinkStatus.LINKED;
        }
        if (body.readable() && body.code() != null && body.code() == NOT_REGISTERED_USER) {
            return KakaoLinkStatus.NOT_LINKED;
        }
        log.warn("카카오 연결 상태를 알 수 없습니다: status={} code={}", status.value(), body.code());
        return KakaoLinkStatus.UNKNOWN;
    }

    static KakaoUnlinkResult classify(HttpStatusCode status, Body body) {
        if (status.is2xxSuccessful()) {
            return KakaoUnlinkResult.UNLINKED;
        }
        if (!body.readable()) {
            log.warn("카카오 연결 해제 오류 응답의 본문을 받지 못했습니다: status={}", status.value());
            return KakaoUnlinkResult.UNKNOWN;
        }
        Integer code = body.code();
        if (code != null && code == NOT_REGISTERED_USER) {
            return KakaoUnlinkResult.UNLINKED;
        }
        if (status.is5xxServerError() || status.value() == 429 || (code != null && TRANSIENT_CODES.contains(code))) {
            log.warn("카카오 연결 해제가 일시 오류로 끝났습니다: status={} code={}", status.value(), code);
            return KakaoUnlinkResult.UNKNOWN;
        }
        if (status.is4xxClientError()) {
            log.error("카카오가 연결 해제를 거절했습니다: status={} code={}", status.value(), code);
            return KakaoUnlinkResult.FAILED;
        }
        log.warn("카카오 연결 해제 응답을 해석하지 못했습니다: status={}", status.value());
        return KakaoUnlinkResult.UNKNOWN;
    }

    private Body readBody(InputStream stream) {
        byte[] bytes;
        try (stream) {
            bytes = stream.readAllBytes();
        } catch (IOException | RuntimeException e) {
            return Body.unreadable();
        }
        try {
            JsonNode code = objectMapper.readTree(bytes).get("code");
            return Body.code(code != null && code.canConvertToInt() ? code.intValue() : null);
        } catch (IOException | RuntimeException e) {
            return Body.code(null);
        }
    }
}
