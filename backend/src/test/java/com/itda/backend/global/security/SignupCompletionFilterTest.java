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
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.UserRepository;

/**
 * 가입을 마치지 않은 회원은 /auth/me · /auth/signup · /auth/logout 외의 API 를 쓸 수 없다.
 *
 * <p>보호 API 마다 확인을 넣는 대신 필터 한 곳에서 막는다. 새 API 가 생겨도 자동으로 적용된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignupCompletionFilterTest {

    private static final String PROTECTED = "/api/v1/raw-records";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

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
}
