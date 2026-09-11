package com.itda.backend.domain.auth.controller;

import com.itda.backend.domain.auth.dto.response.LoginResponse;
import com.itda.backend.domain.auth.service.KakaoAuthService;
import com.itda.backend.global.jwt.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClientException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KakaoAuthService kakaoAuthService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @Test
    void kakaoLoginReturnsJwtAndUserInfo() throws Exception {
        given(kakaoAuthService.login(any()))
                .willReturn(new LoginResponse("jwt-token", 12345L, "홍길동"));

        mockMvc.perform(post("/api/v1/auth/kakao").param("code", "test-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.data.kakaoId").value(12345))
                .andExpect(jsonPath("$.data.nickname").value("홍길동"));
    }

    @Test
    void kakaoLoginReturnsBadRequestWhenKakaoCommunicationFails() throws Exception {
        given(kakaoAuthService.login(any()))
                .willThrow(new RestClientException("카카오 서버 응답 실패"));

        mockMvc.perform(post("/api/v1/auth/kakao").param("code", "invalid-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("KAKAO_AUTH_FAILED"));
    }
}
