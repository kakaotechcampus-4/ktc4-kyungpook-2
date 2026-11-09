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

/**
 * 카카오 연결 해제 ({@code POST /v1/user/unlink}, 서비스 앱 어드민 키). 카카오 정책상 서비스 탈퇴 과정에 반드시 넣어야 한다.
 *
 * <p>응답을 세 가지로 나눈다 ({@link KakaoUnlinkResult}). 카카오는 우리 쪽 처리 번호를 확인해 주지 않으므로, 이 클라이언트를
 * 부르는 곳은 탈퇴 실행기 한 곳뿐이다 ({@link WithdrawalExecutor}).
 */
@Slf4j
@Component
public class KakaoUnlinkClient {

    /** 이미 연결이 끊긴(앱과 연결되지 않은) 사용자. 목표 상태에 이미 와 있다. */
    static final int NOT_REGISTERED_USER = -101;

    /** 카카오 서버 내부 오류 · 점검 · 요청 횟수 초과. 이번 호출이 처리됐는지 알 수 없다. */
    private static final Set<Integer> TRANSIENT_CODES = Set.of(-1, -7, -10);

    private final RestClient restClient;
    private final String adminKey;
    private final ObjectMapper objectMapper;

    public KakaoUnlinkClient(RestClient.Builder builder, WithdrawalProperties properties, ObjectMapper objectMapper) {
        WithdrawalProperties.Kakao kakao = properties.kakao();
        // 복사해서 쓴다. 받은 빌더를 바로 고치면 같은 빌더를 받은 다른 클라이언트(AI 서버)의 주소까지 바뀐다
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
            // 타임아웃 · 연결 끊김. 카카오가 요청을 받아 처리했는지 알 수 없다
            log.warn("카카오 연결 해제 응답을 받지 못했습니다: {}", e.getMessage());
            return KakaoUnlinkResult.UNKNOWN;
        }
    }

    /** 응답 본문. {@code readable} 이 false 면 본문을 끝까지 받지 못했다(읽는 중 타임아웃 · 연결 끊김). */
    record Body(boolean readable, Integer code) {

        static Body code(Integer code) {
            return new Body(true, code);
        }

        static Body unreadable() {
            return new Body(false, null);
        }
    }

    static KakaoUnlinkResult classify(HttpStatusCode status, Body body) {
        if (status.is2xxSuccessful()) {
            return KakaoUnlinkResult.UNLINKED;
        }
        if (!body.readable()) {
            // 오류 상태는 받았지만 본문을 끝까지 받지 못했다. 그 본문이 "이미 끊김(-101)" 이었을 수 있으니 실패로 단정하지 않는다
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

    /**
     * 오류 본문 {@code {"code": -101, "msg": "..."}} 를 읽는다. 받는 단계와 해석 단계를 나눈다 — 끝까지 받지 못하면(타임아웃 ·
     * 연결 끊김) 결과를 모르는 것이고, 다 받았는데 JSON 이 아니거나 code 가 없으면 code 없는 오류다.
     */
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
