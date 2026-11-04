package com.itda.backend.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.TermsAgreement;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.user.TermsCatalog;

/**
 * 가입을 마치지 않은 회원은 /auth/me · /auth/signup · /auth/logout 외의 API 를 쓸 수 없다.
 * 약관에 동의하지 않은 보호자는 여기에 /auth/terms 를 더한 경로만 쓸 수 있다.
 *
 * <p>보호 API 마다 확인을 넣는 대신 필터 한 곳에서 막는다. 새 API 가 생겨도 자동으로 적용된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SignupCompletionFilterTest.ProtectedProbe.class)
class SignupCompletionFilterTest {

    // 회원 서비스를 호출하지 않아도 필터만으로 차단되는지 검증한다.
    private static final String PROTECTED = "/api/v1/security-test/probe";

    @RestController
    static class ProtectedProbe {
        @GetMapping(PROTECTED)
        String probe() {
            return "controller-reached";
        }
    }

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

    private Cookie cookieFor(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId())));
    }

    @Test
    void userBeforeSignupIsForbiddenFromProtectedApi() throws Exception {
        User user = userRepository.save(User.pending("filter-pending-1", "박지현"));

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("SIGNUP_NOT_COMPLETED"));
    }

    /** 가입 화면을 띄우려면 /auth/me 로 상태를 알아야 한다. */
    @Test
    void userBeforeSignupCanReadSession() throws Exception {
        User user = userRepository.save(User.pending("filter-pending-2", "박지현"));

        mockMvc.perform(get("/api/v1/auth/me").cookie(cookieFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupCompleted").value(false));
    }

    /** 가입을 그만두고 나갈 수도 있어야 한다. */
    @Test
    void userBeforeSignupCanLogOut() throws Exception {
        User user = userRepository.save(User.pending("filter-pending-3", "박지현"));
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(cookieFor(user), csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    void userBeforeSignupCanReachHealthCheck() throws Exception {
        User user = userRepository.save(User.pending("filter-pending-4", "박지현"));

        mockMvc.perform(get("/api/health").cookie(cookieFor(user)))
                .andExpect(status().isOk());
    }

    @Test
    void signedUpUserPassesThrough() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User user = userRepository.save(
                UserFixture.organizationUser("filter-org-1", "박지현", organization.getId()));

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(user)))
                .andExpect(status().isOk());
    }

    /* ── 약관 동의 ──────────────────────────────────────── */

    @Test
    void parentBeforeTermsIsForbiddenFromProtectedApi() throws Exception {
        User parent = userRepository.save(UserFixture.parent("filter-terms-1", "김보호"));

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TERMS_NOT_AGREED"));
        // 실제 보호자 API 도 같다
        mockMvc.perform(get("/api/v1/guardians/me/children").cookie(cookieFor(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TERMS_NOT_AGREED"));
    }

    /** 약관 화면을 띄우려면 세션을 읽어야 하고, 동의를 보내야 하고, 그만두고 나갈 수도 있어야 한다. */
    @Test
    void parentBeforeTermsCanReadSessionAgreeAndLogOut() throws Exception {
        User parent = userRepository.save(UserFixture.parent("filter-terms-2", "김보호"));
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(get("/api/v1/auth/me").cookie(cookieFor(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.termsAgreed").value(false));
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(cookieFor(parent), csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/terms")
                        .cookie(cookieFor(parent), csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"agreed\": [\"tos\", \"privacy\"]}"))
                .andExpect(status().isOk());

        // 동의한 뒤에는 통과한다
        mockMvc.perform(get(PROTECTED).cookie(cookieFor(parent)))
                .andExpect(status().isOk());
    }

    @Test
    void parentWhoAgreedPassesThrough() throws Exception {
        User parent = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.parent("filter-terms-3", "김보호")));

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(parent)))
                .andExpect(status().isOk());
    }

    /** 개인정보 수집 약관의 판이 올라가면 옛 판 동의로는 다시 막힌다. */
    @Test
    void parentWithOnlyOldPrivacyVersionIsForbiddenAgain() throws Exception {
        User parent = userRepository.save(UserFixture.parent("filter-terms-4", "김보호"));
        termsAgreementRepository.save(TermsAgreement.agree(
                parent.getId(), TermKey.TOS, TermsCatalog.currentVersion(TermKey.TOS)));
        termsAgreementRepository.save(TermsAgreement.agree(parent.getId(), TermKey.PRIVACY, "2000-01-01"));

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TERMS_NOT_AGREED"));
    }

    /** 이용약관은 판이 올라가도 다시 받지 않는다 — 옛 판 동의로 통과한다. */
    @Test
    void parentWithOnlyOldTosVersionPasses() throws Exception {
        User parent = userRepository.save(UserFixture.parent("filter-terms-5", "김보호"));
        termsAgreementRepository.save(TermsAgreement.agree(parent.getId(), TermKey.TOS, "2000-01-01"));
        termsAgreementRepository.save(TermsAgreement.agree(
                parent.getId(), TermKey.PRIVACY, TermsCatalog.currentVersion(TermKey.PRIVACY)));

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(parent)))
                .andExpect(status().isOk());
    }

    /** 가입하지 않은 회원은 약관보다 가입이 먼저다 — /auth/terms 도 가입 미완료로 막는다. */
    @Test
    void userBeforeSignupGetsSignupErrorEvenOnTermsPath() throws Exception {
        User user = userRepository.save(User.pending("filter-pending-terms", "박지현"));
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/auth/terms")
                        .cookie(cookieFor(user), csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"agreed\": [\"tos\", \"privacy\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_NOT_COMPLETED"));
    }

    /** 비로그인은 가입 여부를 따지기 전에 401 이다. */
    @Test
    void anonymousRequestIsStillUnauthorized() throws Exception {
        mockMvc.perform(get(PROTECTED))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /** 출입증이 없는 회원을 가리키면 가입 미완료가 아니라 기존대로 401 이어야 프론트가 로그인 화면으로 보낸다. */
    @Test
    void tokenForMissingUserIsStillUnauthorized() throws Exception {
        String token = jwtProvider.createToken("99999998");

        mockMvc.perform(get(PROTECTED).cookie(new Cookie(JwtCookie.NAME, token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_USER_NOT_FOUND"));
    }

    @Test
    void tokenForDeletedUserIsUnauthorizedBeforeController() throws Exception {
        User user = UserFixture.parent("filter-deleted", "탈퇴회원");
        user.delete();
        userRepository.saveAndFlush(user);

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(user)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_USER_NOT_FOUND"));
    }

    @Test
    void malformedUserIdIsUnauthorizedBeforeController() throws Exception {
        for (String subject : new String[]{"not-a-number", "9223372036854775808"}) {
            mockMvc.perform(get(PROTECTED).cookie(new Cookie(JwtCookie.NAME, jwtProvider.createToken(subject))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("SESSION_USER_NOT_FOUND"));
        }
    }
}
