package com.itda.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.itda.backend.dto.response.user.CurrentUserResponse;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.user.RevokedSessionService;
import com.itda.backend.service.user.WithdrawalService;
import com.itda.backend.service.user.WithdrawalSteps;
import com.itda.backend.service.user.UserService;

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

    @MockitoBean
    private RevokedSessionService revokedSessionService;

    @MockitoBean
    private WithdrawalService withdrawalService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String userId) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @Test
    void meReturnsRoleUserIdAndInstitutionIdForOrganization() throws Exception {
        authenticateAs("1");
        given(withdrawalService.isEnabled()).willReturn(true);
        given(userService.getCurrentUser("1"))
                .willReturn(new CurrentUserResponse("org", "1", "1", "햇살아동발달센터", true, true, "none", true));

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.role").value("org"))
                .andExpect(jsonPath("$.data.userId").value("1"))
                .andExpect(jsonPath("$.data.name").doesNotExist())
                .andExpect(jsonPath("$.data.institutionId").value("1"))
                .andExpect(jsonPath("$.data.institutionName").value("햇살아동발달센터"))
                .andExpect(jsonPath("$.data.signupCompleted").value(true))
                .andExpect(jsonPath("$.data.withdrawalEnabled").value(true));
    }

    @Test
    void meReportsSignupNotCompletedWithoutRole() throws Exception {
        authenticateAs("3");
        given(userService.getCurrentUser("3"))
                .willReturn(new CurrentUserResponse(null, "3", null, null, false, false, "none", true));

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupCompleted").value(false))
                .andExpect(jsonPath("$.data.role").doesNotExist())
                .andExpect(jsonPath("$.data.institutionId").doesNotExist());
    }

    @Test
    void meOmitsInstitutionIdForParent() throws Exception {
        authenticateAs("2");
        given(userService.getCurrentUser("2"))
                .willReturn(new CurrentUserResponse("parent", "2", null, null, true, false, "none", true));

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("parent"))
                .andExpect(jsonPath("$.data.institutionId").doesNotExist())
                .andExpect(jsonPath("$.data.institutionName").doesNotExist())
                .andExpect(jsonPath("$.data.termsAgreed").value(false));
    }

    @Test
    void logoutReturnsNoContentWithoutBody() throws Exception {
        given(jwtCookie.expire()).willReturn(
                ResponseCookie.from(JwtCookie.NAME, "").maxAge(0).path("/").httpOnly(true).build());

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void withdrawCompletedReturnsNoContentWithoutTouchingCookies() throws Exception {
        authenticateAs("2");
        given(withdrawalService.withdraw("2", "2")).willReturn(WithdrawalSteps.Outcome.COMPLETED);

        mockMvc.perform(delete("/api/v1/auth/me").param("userId", "2"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(revokedSessionService, never()).revoke(any(), any());
    }

    @Test
    void withdrawInProgressReturnsAccepted() throws Exception {
        authenticateAs("2");
        given(withdrawalService.withdraw("2", "2")).willReturn(WithdrawalSteps.Outcome.IN_PROGRESS);

        mockMvc.perform(delete("/api/v1/auth/me").param("userId", "2"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.withdrawal").value("in_progress"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void withdrawWithoutShownAccountIsBadRequest() throws Exception {
        authenticateAs("2");

        mockMvc.perform(delete("/api/v1/auth/me"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withdrawCanceledReturnsBadGateway() throws Exception {
        authenticateAs("2");
        given(withdrawalService.withdraw("2", "2")).willReturn(WithdrawalSteps.Outcome.CANCELED);

        mockMvc.perform(delete("/api/v1/auth/me").param("userId", "2"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("KAKAO_UNLINK_FAILED"));
    }
}
