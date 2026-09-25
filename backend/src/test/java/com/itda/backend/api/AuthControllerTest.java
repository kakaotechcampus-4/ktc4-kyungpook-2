package com.itda.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;

import com.itda.backend.dto.response.CurrentUserResponse;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.UserService;

// addFilters(false) 라 필터 체인이 돌지 않는다 — JwtAuthenticationFilter 가 하는 일
// (SecurityContext principal 에 내부 userId(String) 를 심는 것)을 authenticateAs(...) 로
// 직접 재현한다. 인증 실패(401) 경로는 필터가 있어야 하므로 AuthMeSecurityTest 가 맡는다.
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtCookie jwtCookie;

    @MockitoBean
    private JwtProvider jwtProvider;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String userId) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @Test
    void meReturnsRoleUserIdNameAndInstitutionIdForOrganization() throws Exception {
        authenticateAs("1");
        given(userService.getCurrentUser("1"))
                .willReturn(new CurrentUserResponse("org", "1", "박지현", "1", true));

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.role").value("org"))
                .andExpect(jsonPath("$.data.userId").value("1"))
                .andExpect(jsonPath("$.data.name").value("박지현"))
                .andExpect(jsonPath("$.data.institutionId").value("1"))
                .andExpect(jsonPath("$.data.signupCompleted").value(true));
    }

    /** 가입 미완료 — signupCompleted 는 false 로 반드시 나가고, role 은 빠진다. */
    @Test
    void meReportsSignupNotCompletedWithoutRole() throws Exception {
        authenticateAs("3");
        given(userService.getCurrentUser("3"))
                .willReturn(new CurrentUserResponse(null, "3", "박지현", null, false));

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupCompleted").value(false))
                .andExpect(jsonPath("$.data.role").doesNotExist())
                .andExpect(jsonPath("$.data.institutionId").doesNotExist());
    }

    /** institutionId 는 role 이 org 일 때만 내려간다 — null 로도 실어 보내지 않는다. */
    @Test
    void meOmitsInstitutionIdForParent() throws Exception {
        authenticateAs("2");
        given(userService.getCurrentUser("2"))
                .willReturn(new CurrentUserResponse("parent", "2", "김보호", null, true));

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("parent"))
                .andExpect(jsonPath("$.data.institutionId").doesNotExist());
    }

    /** 204 에는 ApiResponse 래퍼를 쓰지 않는다 (AGENTS.md, api-conventions.md). */
    @Test
    void logoutReturnsNoContentWithoutBody() throws Exception {
        given(jwtCookie.expire()).willReturn(
                ResponseCookie.from(JwtCookie.NAME, "").maxAge(0).path("/").httpOnly(true).build());

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }
}
