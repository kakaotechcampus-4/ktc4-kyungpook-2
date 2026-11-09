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
import com.itda.backend.domain.user.UserRole;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.UserRepository;

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
        return userRepository.save(User.pending(kakaoId));
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
                .andExpect(jsonPath("$.data.termsAgreed").value(false))
                .andExpect(jsonPath("$.data.institutionId").doesNotExist())
                .andExpect(jsonPath("$.data.institutionName").doesNotExist());

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
                .andExpect(jsonPath("$.data.termsAgreed").value(false))
                .andExpect(jsonPath("$.data.institutionId").isString())
                .andExpect(jsonPath("$.data.institutionName").value("햇살아동발달센터"));

        User saved = userRepository.findById(user.getId()).orElseThrow();
        Organization organization = organizationRepository.findById(saved.getOrganizationId()).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(UserRole.ORGANIZATION);
        assertThat(organization.getBusinessNumber()).isEqualTo(businessNumber);
        assertThat(organization.getCode()).hasSize(Organization.CODE_LENGTH);
        assertThat(organization.getName()).isEqualTo("햇살아동발달센터");
    }

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
            "{\"role\": \"org\"}",
            "{\"role\": \"org\", \"organizationType\": \"CENTER\", \"businessNumber\": \"1234567891\"}",
            "{\"role\": \"org\", \"organizationName\": \" \", \"organizationType\": \"CENTER\", \"businessNumber\": \"1234567891\"}",
            "{\"role\": \"org\", \"organizationName\": \"센터\", \"businessNumber\": \"1234567891\"}",
            "{\"role\": \"org\", \"organizationName\": \"센터\", \"organizationType\": \"CENTER\"}",
            "{\"role\": \"org\", \"organizationName\": \"센터\", \"organizationType\": \"CENTER\", \"businessNumber\": \"123456789\"}",
            "{\"role\": \"org\", \"organizationName\": \"센터\", \"organizationType\": \"CENTER\", \"businessNumber\": \"123-45-67890\"}",
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

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"role\": \"parent\", \"organizationName\": \"햇살센터\"}",
            "{\"role\": \"parent\", \"organizationType\": \"CENTER\"}",
            "{\"role\": \"parent\", \"businessNumber\": \"1234567892\"}",
            "{\"role\": \"parent\", \"businessNumber\": \"12\"}",
            "{\"role\": \"parent\", \"organizationName\": \"햇살센터\", \"organizationType\": \"CENTER\", \"businessNumber\": \"1234567892\"}",
            "{\"role\": \"parent\", \"organizationName\": \"\"}",
            "{\"role\": \"parent\", \"businessNumber\": \"\"}",
    })
    void parentSignupWithOrganizationInfoIsBadRequest(String body) throws Exception {
        User user = pendingUser("signup-parent-org-" + Math.abs(body.hashCode()));

        signup(user, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isSignupCompleted()).isFalse();
    }

    @Test
    void parentSignupWithNullOrganizationFieldsIsAccepted() throws Exception {
        User user = pendingUser("signup-parent-nulls");

        signup(user, """
                {"role": "parent", "organizationName": null,
                 "organizationType": null, "businessNumber": null}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("parent"));
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
