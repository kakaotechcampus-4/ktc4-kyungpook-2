package com.itda.backend.global.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Spring Security 가 카카오를 호출할 때 쓰는 두 통로에 타임아웃을 건다.
 *
 * <p>기본 구현에는 타임아웃이 없다. 카카오가 응답을 주지 않으면 요청 스레드가 무한정 묶여,
 * 장애가 로그인에서 끝나지 않고 서버 전체로 번진다. (PR #22 가 직접 구현 시절에 걸어둔
 * 타임아웃을 프레임워크 쪽으로 옮긴 것이다.)
 *
 * <p><b>주의 — 여기서 한 번 깨뜨렸던 부분.</b> 아래 토큰 교환용 RestClient 는 메시지
 * 컨버터를 직접 달아준다. {@code RestClient.builder()} 의 기본 컨버터로는 카카오의 토큰
 * 응답을 {@code OAuth2AccessTokenResponse} 로 해석하지 못해, 액세스 토큰이 비어 있는 채로
 * 넘어가고 {@code "accessToken cannot be null"} 로 터진다.
 * {@code setRestClient()} 로 갈아끼우는 순간 프레임워크가 기본으로 달아주던 컨버터가
 * 통째로 사라지기 때문이다. 아래 세 줄은 Spring Security 가 내부에서 하던 설정과 같다.
 */
@Configuration
public class OAuth2ClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static ClientHttpRequestFactorySettings timeouts() {
        return ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
    }

    /** 인가 코드 → 액세스 토큰 교환 (카카오 token 엔드포인트). */
    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> accessTokenResponseClient() {
        RestClient restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts()))
                .messageConverters(converters -> {
                    converters.clear();
                    // 요청은 form 으로 나가고, 응답은 OAuth2 토큰 규격으로 읽는다.
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                // 카카오가 4xx/5xx 로 답하면 OAuth2 오류로 변환해준다.
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();

        RestClientAuthorizationCodeTokenResponseClient client =
                new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(restClient);
        return client;
    }

    /** 액세스 토큰 → 사용자 정보 조회 (카카오 user-info 엔드포인트). */
    @Bean
    public OAuth2UserService<OAuth2UserRequest, OAuth2User> oAuth2UserService() {
        DefaultOAuth2UserService service = new DefaultOAuth2UserService();
        service.setRestOperations(new RestTemplate(
                ClientHttpRequestFactoryBuilder.detect().build(timeouts())));
        return service;
    }
}
