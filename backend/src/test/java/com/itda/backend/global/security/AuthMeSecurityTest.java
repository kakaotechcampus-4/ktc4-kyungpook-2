package com.itda.backend.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthMeSecurityTest {

    private static final String ME = "/api/v1/auth/me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TermsAgreementRepository termsAgreementRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private Cookie cookieFor(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId())));
    }

    @Test
    void organizationUserGetsRoleAndInstitutionId() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User user = userRepository.save(
                UserFixture.organizationUser("me-org-1", organization.getId()));

        mockMvc.perform(get(ME).cookie(cookieFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.role").value("org"))
                .andExpect(jsonPath("$.data.userId").value(String.valueOf(user.getId())))
                .andExpect(jsonPath("$.data.name").doesNotExist())
                .andExpect(jsonPath("$.data.institutionId").value(String.valueOf(organization.getId())))
                .andExpect(jsonPath("$.data.signupCompleted").value(true));
    }

    @Test
    void userBeforeSignupGetsSignupNotCompletedWithoutRole() throws Exception {
        User user = userRepository.save(User.pending("me-pending-1"));

        mockMvc.perform(get(ME).cookie(cookieFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupCompleted").value(false))
                .andExpect(jsonPath("$.data.userId").value(String.valueOf(user.getId())))
                .andExpect(jsonPath("$.data.name").doesNotExist())
                .andExpect(jsonPath("$.data.role").doesNotExist())
                .andExpect(jsonPath("$.data.institutionId").doesNotExist());
    }

    @Test
    void parentResponseOmitsInstitutionId() throws Exception {
        User user = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent("me-parent-1")));

        mockMvc.perform(get(ME).cookie(cookieFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("parent"))
                .andExpect(jsonPath("$.data.institutionId").doesNotExist());
    }

    @Test
    void userWithoutNicknameOmitsName() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User user = userRepository.save(
                UserFixture.organizationUser("me-org-2", organization.getId()));

        mockMvc.perform(get(ME).cookie(cookieFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").doesNotExist());
    }

    @Test
    void unauthenticatedRequestIsUnauthorized() throws Exception {
        mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void tamperedCookieIsUnauthorized() throws Exception {
        mockMvc.perform(get(ME).cookie(new Cookie(JwtCookie.NAME, "not-a-real-jwt")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenForMissingUserIsUnauthorized() throws Exception {
        String token = jwtProvider.createToken("99999999");

        mockMvc.perform(get(ME).cookie(new Cookie(JwtCookie.NAME, token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_USER_NOT_FOUND"));
    }

    @Test
    void nonNumericSubjectIsUnauthorizedNotServerError() throws Exception {
        String token = jwtProvider.createToken("kakao-legacy-subject");

        mockMvc.perform(get(ME).cookie(new Cookie(JwtCookie.NAME, token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_USER_NOT_FOUND"));
    }

    @Test
    void withdrawnUserIsUnauthorized() throws Exception {
        User user = userRepository.save(UserFixture.parent("me-withdrawn-1"));
        user = userRepository.save(UserFixture.withdrawn(user));

        mockMvc.perform(get(ME).cookie(cookieFor(user)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_WITHDRAWN"));
    }

    @Test
    void withdrawingUserSeesInProgress() throws Exception {
        User user = UserFixture.parent("me-withdrawing-1");
        user.requestWithdrawal(java.time.LocalDateTime.now());
        user = userRepository.save(user);

        mockMvc.perform(get(ME).cookie(cookieFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawal").value("in_progress"));
    }

    @Test
    void authenticatedRequestDoesNotLeaveLoginInSession() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User user = userRepository.save(
                UserFixture.organizationUser("me-session-1", organization.getId()));

        var result = mockMvc.perform(get(ME).cookie(cookieFor(user)))
                .andExpect(status().isOk())
                .andReturn();

        var session = result.getRequest().getSession(false);
        if (session != null) {
            org.assertj.core.api.Assertions.assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
        }
    }

    @Test
    void authenticatedRequestDoesNotExpireCsrfCookie() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User user = userRepository.save(
                UserFixture.organizationUser("me-csrf-1", organization.getId()));
        jakarta.servlet.http.Cookie csrf = mockMvc.perform(get("/api/health"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");

        var response = mockMvc.perform(get(ME).cookie(cookieFor(user), csrf))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        jakarta.servlet.http.Cookie after = response.getCookie("XSRF-TOKEN");
        if (after != null) {
            org.assertj.core.api.Assertions.assertThat(after.getMaxAge())
                    .as("XSRF-TOKEN 을 만료시키면 안 된다").isNotZero();
        }
    }

    @Test
    void sessionAloneDoesNotAuthenticateAfterLogout() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User user = userRepository.save(
                UserFixture.organizationUser("me-session-2", organization.getId()));
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();

        mockMvc.perform(get(ME).cookie(cookieFor(user)).session(session))
                .andExpect(status().isOk());

        mockMvc.perform(get(ME).session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void parentIsForbiddenFromOrganizationOnlyApi() throws Exception {
        User parent = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent("me-parent-2")));

        mockMvc.perform(get("/api/v1/raw-records").cookie(cookieFor(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
    }
}
