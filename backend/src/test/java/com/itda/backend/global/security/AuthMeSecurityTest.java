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

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.UserRepository;

/**
 * GET /api/v1/auth/me 의 성공·인증 실패 경로.
 *
 * <p>SecurityConfig 가 /api/v1/auth/** 를 통째로 열어두고 있었기 때문에, 이 경로를
 * 좁히지 않으면 비로그인 요청이 401 대신 200 을 받는다. 그것을 고정하는 테스트다.
 */
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
    private OrganizationRepository organizationRepository;

    private Cookie cookieFor(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId())));
    }

    @Test
    void organizationUserGetsRoleAndInstitutionId() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User user = userRepository.save(
                UserFixture.organizationUser("me-org-1", "박지현", organization.getId()));

        mockMvc.perform(get(ME).cookie(cookieFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.role").value("org"))
                .andExpect(jsonPath("$.data.userId").value(String.valueOf(user.getId())))
                .andExpect(jsonPath("$.data.name").value("박지현"))
                .andExpect(jsonPath("$.data.institutionId").value(String.valueOf(organization.getId())))
                .andExpect(jsonPath("$.data.signupCompleted").value(true));
    }

    /**
     * 카카오 로그인만 하고 가입을 안 끝낸 회원. 401 이 아니라 200 이다 — 가입 미완료는 정상 상태이고,
     * 프론트는 signupCompleted 만 보고 가입 화면으로 보낸다. role·institutionId 는 아예 없어야 한다.
     */
    @Test
    void userBeforeSignupGetsSignupNotCompletedWithoutRole() throws Exception {
        User user = userRepository.save(User.pending("me-pending-1", "박지현"));

        mockMvc.perform(get(ME).cookie(cookieFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupCompleted").value(false))
                .andExpect(jsonPath("$.data.userId").value(String.valueOf(user.getId())))
                .andExpect(jsonPath("$.data.name").value("박지현"))
                .andExpect(jsonPath("$.data.role").doesNotExist())
                .andExpect(jsonPath("$.data.institutionId").doesNotExist());
    }

    /** institutionId 는 role 이 org 일 때만 나간다. 보호자 응답에는 아예 없어야 한다. */
    @Test
    void parentResponseOmitsInstitutionId() throws Exception {
        User user = userRepository.save(UserFixture.parent("me-parent-1", "김보호"));

        mockMvc.perform(get(ME).cookie(cookieFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("parent"))
                .andExpect(jsonPath("$.data.institutionId").doesNotExist());
    }

    /** 닉네임 동의를 거부한 사용자는 name 이 없다. null 을 실어 보내지 않는다. */
    @Test
    void userWithoutNicknameOmitsName() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User user = userRepository.save(
                UserFixture.organizationUser("me-org-2", null, organization.getId()));

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

    /**
     * 서명은 멀쩡한데 그 사용자가 없는 경우. DB 를 비웠거나, subject 를 userId 로 바꾸기 전에
     * 발급된 토큰(카카오 회원번호가 들어 있다)을 들고 온 경우다.
     * 404 가 아니라 401 이어야 프론트가 "로그인 화면으로" 라는 기존 처리로 흡수한다.
     */
    @Test
    void validTokenForMissingUserIsUnauthorized() throws Exception {
        String token = jwtProvider.createToken("99999999");

        mockMvc.perform(get(ME).cookie(new Cookie(JwtCookie.NAME, token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_USER_NOT_FOUND"));
    }

    /** subject 전환 이전에 발급된 토큰에는 숫자가 아닌 값이 들어올 수도 있다. 500 이 되면 안 된다. */
    @Test
    void nonNumericSubjectIsUnauthorizedNotServerError() throws Exception {
        String token = jwtProvider.createToken("kakao-legacy-subject");

        mockMvc.perform(get(ME).cookie(new Cookie(JwtCookie.NAME, token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_USER_NOT_FOUND"));
    }

    /** 탈퇴한 사용자의 출입증은 아직 만료 전이어도 인정하지 않는다. */
    @Test
    void withdrawnUserIsUnauthorized() throws Exception {
        User user = UserFixture.parent("me-withdrawn-1", "탈퇴자");
        user.delete();
        user = userRepository.save(user);

        mockMvc.perform(get(ME).cookie(cookieFor(user)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_USER_NOT_FOUND"));
    }

    /**
     * 출입증으로 인증된 요청이 세션에 로그인 정보를 남기면 안 된다.
     *
     * <p>남기면 로그아웃으로 출입증을 지워도 JSESSIONID 만으로 계속 로그인된다.
     * 실제로 그랬다 — SessionManagementFilter 가 JWT 인증을 "세션에 없는 새 로그인" 으로 보고
     * 매 요청 세션에 저장하고 있었다.
     */
    @Test
    void authenticatedRequestDoesNotLeaveLoginInSession() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User user = userRepository.save(
                UserFixture.organizationUser("me-session-1", "세션", organization.getId()));

        var result = mockMvc.perform(get(ME).cookie(cookieFor(user)))
                .andExpect(status().isOk())
                .andReturn();

        var session = result.getRequest().getSession(false);
        if (session != null) {
            org.assertj.core.api.Assertions.assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
        }
    }

    /**
     * 로그인한 상태의 GET 이 CSRF 쿠키를 지우면 안 된다.
     *
     * <p>지우면 프론트가 다음 쓰기 요청(업로드·로그아웃)에 실을 토큰이 없어 403 이 난다.
     * 프론트 라우트 가드가 화면마다 /auth/me 를 부르므로 사실상 모든 쓰기 요청이 깨진다.
     * SessionManagementFilter 가 출입증 인증을 매번 "새 로그인" 으로 보고
     * CsrfAuthenticationStrategy 로 토큰을 비우고 있었다.
     */
    @Test
    void authenticatedRequestDoesNotExpireCsrfCookie() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User user = userRepository.save(
                UserFixture.organizationUser("me-csrf-1", "토큰", organization.getId()));
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

    /** 로그아웃 뒤에는 같은 세션을 들고 와도 로그인이 인정되지 않아야 한다. */
    @Test
    void sessionAloneDoesNotAuthenticateAfterLogout() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User user = userRepository.save(
                UserFixture.organizationUser("me-session-2", "세션", organization.getId()));
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();

        mockMvc.perform(get(ME).cookie(cookieFor(user)).session(session))
                .andExpect(status().isOk());

        // 출입증 없이 같은 세션만 들고 온다 (로그아웃으로 쿠키가 지워진 상태)
        mockMvc.perform(get(ME).session(session))
                .andExpect(status().isUnauthorized());
    }

    /** 기관 소속이 아닌 사용자는 기관 전용 API 에서 403 으로 막힌다. */
    @Test
    void parentIsForbiddenFromOrganizationOnlyApi() throws Exception {
        User parent = userRepository.save(UserFixture.parent("me-parent-2", "김보호"));

        mockMvc.perform(get("/api/v1/raw-records").cookie(cookieFor(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
    }
}
