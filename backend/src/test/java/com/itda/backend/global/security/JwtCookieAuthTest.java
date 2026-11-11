package com.itda.backend.global.security;

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
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtCookieAuthTest {

    private static final String PROTECTED = "/api/v1/raw-records";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private TermsAgreementRepository termsAgreementRepository;

    private String tokenForOrganizationUser(String kakaoId) {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User user = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(
                UserFixture.organizationUser(kakaoId, organization.getId())));
        return jwtProvider.createToken(String.valueOf(user.getId()));
    }

    @Test
    void protectedPathWithoutCookieIsUnauthorized() throws Exception {
        mockMvc.perform(get(PROTECTED))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedPathWithValidCookiePassesAuthentication() throws Exception {
        String token = tokenForOrganizationUser("384921");

        mockMvc.perform(get(PROTECTED).cookie(new Cookie(JwtCookie.NAME, token)))
                .andExpect(status().isOk());
    }

    @Test
    void tamperedCookieIsRejected() throws Exception {
        mockMvc.perform(get(PROTECTED).cookie(new Cookie(JwtCookie.NAME, "not-a-real-jwt")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authorizationHeaderNoLongerAuthenticates() throws Exception {
        String token = tokenForOrganizationUser("384922");

        mockMvc.perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getRequestIssuesCsrfCookieSoTheFrontendCanObtainAToken() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    @Test
    void logoutExpiresTheCookieUsingARealCsrfToken() throws Exception {
        Cookie csrfCookie = mockMvc.perform(get("/api/health"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(JwtCookie.NAME, 0))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")));
    }

    @Test
    void stateChangingRequestWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isForbidden());
    }
}
