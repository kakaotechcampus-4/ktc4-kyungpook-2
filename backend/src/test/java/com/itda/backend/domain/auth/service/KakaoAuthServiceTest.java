package com.itda.backend.domain.auth.service;

import com.itda.backend.domain.auth.exception.AuthErrorCode;
import com.itda.backend.domain.auth.exception.AuthException;
import com.itda.backend.global.jwt.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoAuthServiceTest {

    @Test
    void 카카오_사용자_정보_응답이_비어있으면_인증_실패로_처리한다() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        KakaoAuthService kakaoAuthService = new KakaoAuthService(
                restClientBuilder.build(),
                new JwtProvider("test-secret-key-for-jwt-unit-test-1234", 60_000),
                "test-client-id",
                "test-client-secret",
                "http://localhost:5173/oauth/kakao/callback"
        );

        server.expect(once(), requestTo("https://kauth.kakao.com/oauth/token"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"access_token\":\"kakao-access-token\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://kapi.kakao.com/v2/user/me"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer kakao-access-token"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> kakaoAuthService.login("authorization-code"))
                .isInstanceOfSatisfying(AuthException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.KAKAO_AUTH_FAILED));

        server.verify();
    }
}
