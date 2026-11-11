package com.itda.backend.global.security.oauth;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuth2LoginFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void authorizationEndpointRedirectsToKakao() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/kakao"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",
                        startsWith("https://kauth.kakao.com/oauth/authorize")));
    }

    @Test
    void authorizationUrlCarriesState() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/kakao"))
                .andExpect(header().string("Location", containsString("state=")))
                .andExpect(header().string("Location", containsString("client_id=test-client-id")))
                .andExpect(header().string("Location",
                        containsString("redirect_uri=http://localhost/login/oauth2/code/kakao")));
    }

    @Test
    void callbackWithoutStateIsRejected() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/kakao").param("code", "attacker-code"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login?error=login_failed"));
    }

    @Test
    void callbackWithForgedStateIsRejected() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/kakao")
                        .param("code", "attacker-code")
                        .param("state", "forged-state-value"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login?error=login_failed"));
    }

    @Test
    void userCancellationIsRejected() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/kakao").param("error", "access_denied"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login?error=login_failed"));
    }
}
