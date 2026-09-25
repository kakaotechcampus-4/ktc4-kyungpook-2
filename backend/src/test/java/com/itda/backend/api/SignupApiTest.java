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

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.User;
import com.itda.backend.domain.UserRole;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.UserRepository;

/**
 * POST /api/v1/auth/signup — 보안 필터·검증·예외 처리까지 실제로 거친다.
 *
 * <p>CSRF 는 {@code csrf()} 헬퍼로 주입하지 않고, 프론트처럼 XSRF-TOKEN 쿠키를 받아 헤더로 되돌려보낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignupApiTest {

    private static final String SIGNUP = "/api/v1/auth/signup";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private User pendingUser(String kakaoId) {
        return userRepository.save(User.pending(kakaoId, "박지현"));
    }

    private Cookie cookieFor(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId())));
    }

    private ResultActions signup(User user, String body) throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return mockMvc.perform(post(SIGNUP)
                .cookie(cookieFor(user), csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String organizationBody(String businessNumber) {
        return """
                {"role": "org", "organizationName": "햇살아동발달센터",
                 "organizationType": "CENTER", "businessNumber": "%s"}
                """.formatted(businessNumber);
    }

    @Test
    void parentSignupReturnsCreatedWithSignedUpSession() throws Exception {
        User user = pendingUser("signup-parent-1");

        signup(user, """
                {"role": "parent"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.role").value("parent"))
                .andExpect(jsonPath("$.data.userId").value(String.valueOf(user.getId())))
                .andExpect(jsonPath("$.data.signupCompleted").value(true))
                .andExpect(jsonPath("$.data.institutionId").doesNotExist());

        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(UserRole.PARENT);
        assertThat(saved.getOrganizationId()).isNull();
    }

    @Test
    void organizationSignupCreatesOrganizationAndReturnsItsId() throws Exception {
        User user = pendingUser("signup-org-1");
        String businessNumber = OrganizationFixture.nextBusinessNumber();

        signup(user, organizationBody(businessNumber))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("org"))
                .andExpect(jsonPath("$.data.signupCompleted").value(true))
                .andExpect(jsonPath("$.data.institutionId").isString());

        User saved = userRepository.findById(user.getId()).orElseThrow();
        Organization organization = organizationRepository.findById(saved.getOrganizationId()).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(UserRole.ORGANIZATION);
        assertThat(organization.getBusinessNumber()).isEqualTo(businessNumber);
        assertThat(organization.getName()).isEqualTo("햇살아동발달센터");
    }

    /** 사업자등록번호는 형식만 본다 — 체크섬이 틀린 "1234567890" 같은 값도 통과한다. */
    @Test
    void businessNumberChecksumIsNotVerified() throws Exception {
        User user = pendingUser("signup-org-checksum");

        signup(user, organizationBody("1234567890"))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"role\": \"admin\"}",
            "{\"role\": \"ORG\"}",
            // 기관인데 기관 정보가 빠졌다
            "{\"role\": \"org\"}",
            "{\"role\": \"org\", \"organizationType\": \"CENTER\", \"businessNumber\": \"1234567891\"}",
            "{\"role\": \"org\", \"organizationName\": \" \", \"organizationType\": \"CENTER\", \"businessNumber\": \"1234567891\"}",
            "{\"role\": \"org\", \"organizationName\": \"센터\", \"businessNumber\": \"1234567891\"}",
            "{\"role\": \"org\", \"organizationName\": \"센터\", \"organizationType\": \"CENTER\"}",
            // 사업자등록번호 형식
            "{\"role\": \"org\", \"organizationName\": \"센터\", \"organizationType\": \"CENTER\", \"businessNumber\": \"123456789\"}",
            "{\"role\": \"org\", \"organizationName\": \"센터\", \"organizationType\": \"CENTER\", \"businessNumber\": \"123-45-67890\"}",
            // enum 에 없는 기관 유형
            "{\"role\": \"org\", \"organizationName\": \"센터\", \"organizationType\": \"HOSPITAL\", \"businessNumber\": \"1234567891\"}",
    })
    void invalidRequestIsBadRequest(String body) throws Exception {
        User user = pendingUser("signup-invalid-" + Math.abs(body.hashCode()));

        signup(user, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isSignupCompleted()).isFalse();
    }

    /**
     * 보호자는 role 만 보낸다. 기관 필드가 하나라도 섞이면 형식이 맞든 틀리든 400 이다 —
     * 조용히 무시하면 "기관 정보를 보냈는데 기관이 안 생겼다" 는 착각이 생긴다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"role\": \"parent\", \"organizationName\": \"햇살센터\"}",
            "{\"role\": \"parent\", \"organizationType\": \"CENTER\"}",
            "{\"role\": \"parent\", \"businessNumber\": \"1234567892\"}",
            "{\"role\": \"parent\", \"businessNumber\": \"12\"}",
            "{\"role\": \"parent\", \"organizationName\": \"햇살센터\", \"organizationType\": \"CENTER\", \"businessNumber\": \"1234567892\"}",
    })
    void parentSignupWithOrganizationInfoIsBadRequest(String body) throws Exception {
        User user = pendingUser("signup-parent-org-" + Math.abs(body.hashCode()));

        signup(user, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isSignupCompleted()).isFalse();
    }

    @Test
    void organizationNameLongerThanColumnIsBadRequest() throws Exception {
        User user = pendingUser("signup-long-name");

        signup(user, """
                {"role": "org", "organizationName": "%s",
                 "organizationType": "CENTER", "businessNumber": "%s"}
                """.formatted("가".repeat(101), OrganizationFixture.nextBusinessNumber()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void signingUpTwiceIsConflict() throws Exception {
        User user = pendingUser("signup-twice");
        signup(user, "{\"role\": \"parent\"}").andExpect(status().isCreated());

        signup(user, organizationBody(OrganizationFixture.nextBusinessNumber()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SIGNED_UP"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(UserRole.PARENT);
    }

    @Test
    void alreadyRegisteredBusinessNumberIsConflict() throws Exception {
        String businessNumber = OrganizationFixture.nextBusinessNumber();
        signup(pendingUser("signup-dup-1"), organizationBody(businessNumber)).andExpect(status().isCreated());
        User second = pendingUser("signup-dup-2");

        signup(second, organizationBody(businessNumber))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BUSINESS_NUMBER"));

        assertThat(userRepository.findById(second.getId()).orElseThrow().isSignupCompleted()).isFalse();
    }

    @Test
    void signupWithoutLoginIsUnauthorized() throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post(SIGNUP)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"parent\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /** 쿠키 인증이라 CSRF 토큰 없는 가입 요청은 막혀야 한다. */
    @Test
    void signupWithoutCsrfTokenIsForbidden() throws Exception {
        User user = pendingUser("signup-no-csrf");

        mockMvc.perform(post(SIGNUP)
                        .cookie(cookieFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"parent\"}"))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(user.getId()).orElseThrow().isSignupCompleted()).isFalse();
    }
}
