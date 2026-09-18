package com.itda.backend.domain.auth.flow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 멘토 리뷰(PR #18, AuthController:25)의 요구 — "로그인한 브라우저에 묶인 state 검증" —
 * 이 Spring Security 로 전환한 뒤에도 실제로 걸려 있는지 확인한다.
 *
 * <p>state 를 우리가 만들지 않게 됐다고 검증까지 없어진 게 아니라는 걸 고정해 두는 테스트다.
 * 설정이 잘못되면 (예: oauth2Login 을 빼먹으면) 여기가 먼저 깨진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuth2LoginFlowTest {

    @Autowired
    private MockMvc mockMvc;

    /** 로그인 진입점은 우리가 만들지 않았는데도 존재한다 — Spring Security 가 제공한다. */
    @Test
    void authorizationEndpointRedirectsToKakao() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/kakao"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",
                        startsWith("https://kauth.kakao.com/oauth/authorize")));
    }

    /** 인가 URL 에 state 가 실려 나간다. 이게 없으면 CSRF 방어가 성립하지 않는다. */
    @Test
    void authorizationUrlCarriesState() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/kakao"))
                .andExpect(header().string("Location", containsString("state=")))
                .andExpect(header().string("Location", containsString("client_id=test-client-id")))
                .andExpect(header().string("Location",
                        containsString("redirect_uri=http://localhost/login/oauth2/code/kakao")));
    }

    /**
     * 공격의 핵심 경로 — 공격자가 자기 code 를 피해자 브라우저에 들려보내는 경우.
     * 피해자 세션에는 그 state 가 없으므로 카카오에 code 를 보내기 전에 막힌다.
     */
    @Test
    void callbackWithoutStateIsRejected() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/kakao").param("code", "attacker-code"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login?error=login_failed"));
    }

    /** 공격자가 state 를 아무 값이나 지어내도 마찬가지다. */
    @Test
    void callbackWithForgedStateIsRejected() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/kakao")
                        .param("code", "attacker-code")
                        .param("state", "forged-state-value"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login?error=login_failed"));
    }

    /** 사용자가 동의 화면에서 취소한 경우도 실패 핸들러로 온다. */
    @Test
    void userCancellationIsRejected() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/kakao").param("error", "access_denied"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login?error=login_failed"));
    }
}
