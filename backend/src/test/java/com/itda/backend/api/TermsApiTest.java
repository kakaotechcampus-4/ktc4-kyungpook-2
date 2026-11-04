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
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;

/**
 * POST /api/v1/auth/terms — 보안 필터·검증·예외 처리까지 실제로 거친다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TermsApiTest {

    private static final String TERMS = "/api/v1/auth/terms";
    private static final String BOTH = """
            {"agreed": ["tos", "privacy"]}
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
        User parent = userRepository.save(UserFixture.parent("terms-api-1", "김보호"));
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
        User parent = userRepository.save(UserFixture.parent("terms-api-2", "김보호"));
        agree(parent, BOTH).andExpect(status().isOk());

        agree(parent, BOTH)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.termsAgreed").value(true));
        assertThat(termsAgreementRepository.findByUserId(parent.getId())).hasSize(2);
    }

    /** 필수 누락 · 모르는 키(예전 선택 항목 notify 포함) · 빈 목록 · null 원소 · 필드 없음은 모두 400 이고 아무것도 남지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"agreed\": [\"tos\"]}",
            "{\"agreed\": [\"tos\", \"privacy\", \"notify\"]}",
            "{\"agreed\": []}",
            "{\"agreed\": [\"tos\", null, \"privacy\"]}",
            "{}"
    })
    void 잘못된_요청은_400이다(String body) throws Exception {
        User parent = userRepository.save(UserFixture.parent("terms-api-bad-" + Math.abs(body.hashCode()), "김보호"));

        agree(parent, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(termsAgreementRepository.findByUserId(parent.getId())).isEmpty();
    }

    @Test
    void 기관_담당자는_403_PARENT_REQUIRED다() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User staff = userRepository.save(
                UserFixture.organizationUser("terms-api-staff", "박지현", organization.getId()));

        agree(staff, BOTH)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
        assertThat(termsAgreementRepository.findByUserId(staff.getId())).isEmpty();
    }

    @Test
    void 기관_담당자의_세션은_늘_동의한_것이다() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User staff = userRepository.save(
                UserFixture.organizationUser("terms-api-staff-2", "박지현", organization.getId()));

        mockMvc.perform(get("/api/v1/auth/me").cookie(cookieFor(staff)))
                .andExpect(jsonPath("$.data.termsAgreed").value(true));
    }

    /** 가입 전에는 약관보다 가입이 먼저다 — 가입 미완료 차단이 그대로 막는다. */
    @Test
    void 가입_미완료_회원은_403_SIGNUP_NOT_COMPLETED다() throws Exception {
        User pending = userRepository.save(User.pending("terms-api-pending", "박지현"));

        agree(pending, BOTH)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_NOT_COMPLETED"));
    }
}
