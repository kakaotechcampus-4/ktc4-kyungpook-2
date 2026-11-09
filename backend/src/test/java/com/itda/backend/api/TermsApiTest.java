package com.itda.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.TermsAgreement;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
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
class TermsApiTest {

    private static final String TERMS = "/api/v1/auth/terms";
    private static final String BOTH = """
            {"agreed": ["tos", "privacy"]}
            """;
    private static final String ORG_ALL = """
            {"agreed": ["org_tos", "org_privacy", "org_child_data"]}
            """;

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

    private ResultActions agree(User user, String body) throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return mockMvc.perform(post(TERMS)
                .cookie(cookieFor(user), csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void 보호자가_동의하면_저장하고_세션에_동의가_보인다() throws Exception {
        User parent = userRepository.save(UserFixture.parent("terms-api-1"));
        mockMvc.perform(get("/api/v1/auth/me").cookie(cookieFor(parent)))
                .andExpect(jsonPath("$.data.termsAgreed").value(false));

        agree(parent, BOTH)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.termsAgreed").value(true));

        assertThat(termsAgreementRepository.findByUserId(parent.getId())).hasSize(2);
        mockMvc.perform(get("/api/v1/auth/me").cookie(cookieFor(parent)))
                .andExpect(jsonPath("$.data.termsAgreed").value(true));
    }

    @Test
    void 같은_판에_다시_동의해도_같은_응답이고_줄이_늘지_않는다() throws Exception {
        User parent = userRepository.save(UserFixture.parent("terms-api-2"));
        agree(parent, BOTH).andExpect(status().isOk());

        agree(parent, BOTH)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.termsAgreed").value(true));
        assertThat(termsAgreementRepository.findByUserId(parent.getId())).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"agreed\": [\"tos\"]}",
            "{\"agreed\": [\"tos\", \"privacy\", \"notify\"]}",
            "{\"agreed\": []}",
            "{\"agreed\": [\"tos\", null, \"privacy\"]}",
            "{}"
    })
    void 잘못된_요청은_400이다(String body) throws Exception {
        User parent = userRepository.save(UserFixture.parent("terms-api-bad-" + Math.abs(body.hashCode())));

        agree(parent, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(termsAgreementRepository.findByUserId(parent.getId())).isEmpty();
    }

    @Test
    void 기관_담당자가_기관_약관에_동의하면_저장하고_세션에_동의가_보인다() throws Exception {
        User staff = staff("terms-api-staff");
        mockMvc.perform(get("/api/v1/auth/me").cookie(cookieFor(staff)))
                .andExpect(jsonPath("$.data.termsAgreed").value(false));

        agree(staff, ORG_ALL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.termsAgreed").value(true));

        assertThat(termsAgreementRepository.findByUserId(staff.getId())).hasSize(3);
        mockMvc.perform(get("/api/v1/auth/me").cookie(cookieFor(staff)))
                .andExpect(jsonPath("$.data.termsAgreed").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"agreed\": [\"tos\", \"privacy\"]}",
            "{\"agreed\": [\"org_tos\", \"org_privacy\"]}",
            "{\"agreed\": [\"org_tos\", \"org_privacy\", \"org_child_data\", \"tos\"]}"
    })
    void 기관_담당자의_잘못된_약관은_400이다(String body) throws Exception {
        User staff = staff("terms-api-staff-bad-" + Math.abs(body.hashCode()));

        agree(staff, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(termsAgreementRepository.findByUserId(staff.getId())).isEmpty();
    }

    @Test
    void 보호자가_기관_약관을_섞으면_400이다() throws Exception {
        User parent = userRepository.save(UserFixture.parent("terms-api-mixed"));

        agree(parent, "{\"agreed\": [\"tos\", \"privacy\", \"org_child_data\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(termsAgreementRepository.findByUserId(parent.getId())).isEmpty();
    }

    @Test
    void 가입_미완료_회원은_403_SIGNUP_NOT_COMPLETED다() throws Exception {
        User pending = userRepository.save(User.pending("terms-api-pending"));

        agree(pending, BOTH)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_NOT_COMPLETED"));
    }

    @Test
    void 읽은_판을_보내면_그_판으로_기록한다() throws Exception {
        User parent = userRepository.save(UserFixture.parent("terms-api-read"));
        String body = "{\"agreed\": [\"tos\", \"privacy\"], \"versions\": {\"tos\": \"%s\", \"privacy\": \"%s\"}}"
                .formatted(TermsCatalog.currentVersion(TermKey.TOS), TermsCatalog.currentVersion(TermKey.PRIVACY));

        agree(parent, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.termsAgreed").value(true));
        assertThat(termsAgreementRepository.findByUserId(parent.getId()))
                .extracting(TermsAgreement::getTermKey, TermsAgreement::getVersion)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(TermKey.TOS, TermsCatalog.currentVersion(TermKey.TOS)),
                        org.assertj.core.groups.Tuple.tuple(TermKey.PRIVACY, TermsCatalog.currentVersion(TermKey.PRIVACY)));
    }

    @Test
    void 판_없이_동의하면_라벨_판으로_기록한다() throws Exception {
        User parent = userRepository.save(UserFixture.parent("terms-api-label"));

        agree(parent, BOTH).andExpect(status().isOk());
        assertThat(termsAgreementRepository.findByUserId(parent.getId()))
                .extracting(TermsAgreement::getVersion)
                .containsOnly(TermsCatalog.labelOnlyVersion(TermKey.TOS));
    }

    @Test
    void 읽은_판이_지금_판과_다르면_409_TERMS_VERSION_CHANGED다() throws Exception {
        User parent = userRepository.save(UserFixture.parent("terms-api-stale"));

        agree(parent, "{\"agreed\": [\"tos\", \"privacy\"], \"versions\": {\"tos\": \"2000-01-01\", \"privacy\": \"%s\"}}"
                .formatted(TermsCatalog.currentVersion(TermKey.PRIVACY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMS_VERSION_CHANGED"));
        assertThat(termsAgreementRepository.findByUserId(parent.getId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"agreed\": [\"tos\", \"privacy\"], \"versions\": {\"tos\": \"2026-11-07\"}}",
            "{\"agreed\": [\"tos\", \"privacy\"], \"versions\": {\"tos\": \"2026-11-07\", \"privacy\": \"2026-11-07\", \"notify\": \"2026-11-07\"}}",
            "{\"agreed\": [\"tos\", \"privacy\"], \"versions\": {\"tos\": \"2026-11-07\", \"privacy\": null}}"
    })
    void 판의_약관이_동의한_약관과_다르면_400이다(String body) throws Exception {
        User parent = userRepository.save(UserFixture.parent("terms-api-vkeys-" + body.hashCode()));

        agree(parent, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(termsAgreementRepository.findByUserId(parent.getId())).isEmpty();
    }

    private User staff(String kakaoId) {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        return userRepository.save(UserFixture.organizationUser(kakaoId, organization.getId()));
    }
}
