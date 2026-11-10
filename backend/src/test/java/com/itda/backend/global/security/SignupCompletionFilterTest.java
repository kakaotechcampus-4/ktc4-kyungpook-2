package com.itda.backend.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import com.itda.backend.domain.user.UserRole;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.user.TermsCatalog;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SignupCompletionFilterTest.ProtectedProbe.class)
class SignupCompletionFilterTest {

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
        User user = userRepository.save(User.pending("filter-pending-1"));

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("SIGNUP_NOT_COMPLETED"));
    }

    @Test
    void userBeforeSignupCanReadSession() throws Exception {
        User user = userRepository.save(User.pending("filter-pending-2"));

        mockMvc.perform(get("/api/v1/auth/me").cookie(cookieFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupCompleted").value(false));
    }

    @Test
    void userBeforeSignupCanLogOut() throws Exception {
        User user = userRepository.save(User.pending("filter-pending-3"));
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(cookieFor(user), csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    void userBeforeSignupCanReachHealthCheck() throws Exception {
        User user = userRepository.save(User.pending("filter-pending-4"));

        mockMvc.perform(get("/api/health").cookie(cookieFor(user)))
                .andExpect(status().isOk());
    }

    @Test
    void userBeforeSignupOrTermsCanReadTermsDocuments() throws Exception {
        User pending = userRepository.save(User.pending("filter-pending-terms-doc"));
        User parent = userRepository.save(UserFixture.parent("filter-parent-terms-doc"));

        for (User user : new User[]{pending, parent}) {
            mockMvc.perform(get("/api/v1/terms").param("role", "parent").cookie(cookieFor(user)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void pathsBelowTermsDocumentsAreNotOpened() throws Exception {
        User parent = userRepository.save(UserFixture.parent("filter-parent-terms-sub"));

        mockMvc.perform(get("/api/v1/terms/tos").cookie(cookieFor(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TERMS_NOT_AGREED"));
    }

    @Test
    void signedUpUserPassesThrough() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User user = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(
                UserFixture.organizationUser("filter-org-1", organization.getId())));

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(user)))
                .andExpect(status().isOk());
    }

    @Test
    void parentBeforeTermsIsForbiddenFromProtectedApi() throws Exception {
        User parent = userRepository.save(UserFixture.parent("filter-terms-1"));

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TERMS_NOT_AGREED"));
        mockMvc.perform(get("/api/v1/guardians/me/children").cookie(cookieFor(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TERMS_NOT_AGREED"));
    }

    @Test
    void parentBeforeTermsCanReadSessionAgreeAndLogOut() throws Exception {
        User parent = userRepository.save(UserFixture.parent("filter-terms-2"));
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

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(parent)))
                .andExpect(status().isOk());
    }

    @Test
    void parentWhoAgreedPassesThrough() throws Exception {
        User parent = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.parent("filter-terms-3")));

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(parent)))
                .andExpect(status().isOk());
    }

    @Test
    void parentWithOnlyOldPrivacyVersionIsForbiddenAgain() throws Exception {
        User parent = userRepository.save(UserFixture.parent("filter-terms-4"));
        termsAgreementRepository.save(TermsAgreement.agree(
                parent.getId(), TermKey.TOS, TermsCatalog.currentVersion(TermKey.TOS)));
        termsAgreementRepository.save(TermsAgreement.agree(parent.getId(), TermKey.PRIVACY, "2000-01-01"));

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TERMS_NOT_AGREED"));
    }

    @Test
    void parentWithOnlyOldTosVersionPasses() throws Exception {
        User parent = userRepository.save(UserFixture.parent("filter-terms-5"));
        termsAgreementRepository.save(TermsAgreement.agree(parent.getId(), TermKey.TOS, "2000-01-01"));
        termsAgreementRepository.save(TermsAgreement.agree(
                parent.getId(), TermKey.PRIVACY, TermsCatalog.currentVersion(TermKey.PRIVACY)));

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(parent)))
                .andExpect(status().isOk());
    }

    @Test
    void organizationBeforeTermsIsForbiddenFromProtectedApi() throws Exception {
        User staff = staff("filter-org-terms-1");

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TERMS_NOT_AGREED"));
        mockMvc.perform(get("/api/v1/institutions/me").cookie(cookieFor(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TERMS_NOT_AGREED"));
    }

    @Test
    void organizationBeforeTermsCanReadSessionAgreeAndLogOut() throws Exception {
        User staff = staff("filter-org-terms-2");
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(get("/api/v1/auth/me").cookie(cookieFor(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.termsAgreed").value(false));
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(cookieFor(staff), csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/terms")
                        .cookie(cookieFor(staff), csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"agreed\": [\"org_tos\", \"org_privacy\", \"org_child_data\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(staff)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = TermKey.class, names = {"ORG_PRIVACY", "ORG_CHILD_DATA"})
    void organizationWithOldReconsentVersionIsForbiddenAgain(TermKey oldKey) throws Exception {
        User staff = staff("filter-org-terms-old-" + oldKey);
        agreeOrgWithOld(staff, oldKey);

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TERMS_NOT_AGREED"));
    }

    @Test
    void organizationWithOnlyOldTosVersionPasses() throws Exception {
        User staff = staff("filter-org-terms-3");
        agreeOrgWithOld(staff, TermKey.ORG_TOS);

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(staff)))
                .andExpect(status().isOk());
    }

    private User staff(String kakaoId) {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        return userRepository.save(UserFixture.organizationUser(kakaoId, organization.getId()));
    }

    private void agreeOrgWithOld(User staff, TermKey oldKey) {
        for (TermKey key : TermsCatalog.required(UserRole.ORGANIZATION)) {
            termsAgreementRepository.save(TermsAgreement.agree(
                    staff.getId(), key, key == oldKey ? "2000-01-01" : TermsCatalog.currentVersion(key)));
        }
    }

    @Test
    void userBeforeSignupGetsSignupErrorEvenOnTermsPath() throws Exception {
        User user = userRepository.save(User.pending("filter-pending-terms"));
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/auth/terms")
                        .cookie(cookieFor(user), csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"agreed\": [\"tos\", \"privacy\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_NOT_COMPLETED"));
    }

    @Test
    void anonymousRequestIsStillUnauthorized() throws Exception {
        mockMvc.perform(get(PROTECTED))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void tokenForMissingUserIsStillUnauthorized() throws Exception {
        String token = jwtProvider.createToken("99999998");

        mockMvc.perform(get(PROTECTED).cookie(new Cookie(JwtCookie.NAME, token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_USER_NOT_FOUND"));
    }

    @Test
    void tokenForDeletedUserIsUnauthorizedBeforeController() throws Exception {
        User user = userRepository.saveAndFlush(UserFixture.parent("filter-deleted"));
        userRepository.saveAndFlush(UserFixture.withdrawn(user));

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(user)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_WITHDRAWN"));
    }

    @Test
    void userWithdrawingIsConflictBeforeSignupAndTermsChecks() throws Exception {
        User user = UserFixture.parent("filter-withdrawing");
        user.requestWithdrawal(java.time.LocalDateTime.now());
        userRepository.saveAndFlush(user);

        mockMvc.perform(get(PROTECTED).cookie(cookieFor(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WITHDRAWAL_IN_PROGRESS"));
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
