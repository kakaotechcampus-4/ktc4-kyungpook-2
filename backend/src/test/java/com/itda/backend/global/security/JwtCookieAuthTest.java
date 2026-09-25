package com.itda.backend.global.security;

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.User;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.UserRepository;
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

/**
 * 출입증이 헤더에서 쿠키로 옮겨간 뒤의 인증 동작.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtCookieAuthTest {

    /** 실제로 존재하는 보호 엔드포인트. 없는 경로를 쓰면 인증 통과 여부와 무관하게 오류가 난다. */
    private static final String PROTECTED = "/api/v1/raw-records";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    /**
     * 출입증의 subject 는 내부 userId 다. 실제로 존재하는 기관 담당자를 만들어 토큰을 발급한다 —
     * 아무 숫자나 넣으면 보호 API 가 사용자를 찾지 못해 401 이 된다.
     */
    private String tokenForOrganizationUser(String kakaoId) {
        Organization organization = organizationRepository.findFirstByOrderByIdAsc().orElseThrow();
        User user = userRepository.save(
                UserFixture.organizationUser(kakaoId, "테스트", organization.getId()));
        return jwtProvider.createToken(String.valueOf(user.getId()));
    }

    @Test
    void protectedPathWithoutCookieIsUnauthorized() throws Exception {
        mockMvc.perform(get(PROTECTED))
                .andExpect(status().isUnauthorized());
    }

    /** 쿠키만으로 인증이 선다. 프론트는 아무것도 붙이지 않는다. */
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

    /**
     * Authorization 헤더는 더 이상 받지 않는다.
     * 프론트가 옛 방식으로 붙여 보내도 인증되지 않는다는 걸 고정해 둔다.
     */
    @Test
    void authorizationHeaderNoLongerAuthenticates() throws Exception {
        String token = tokenForOrganizationUser("384922");

        mockMvc.perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 프론트가 CSRF 토큰을 실제로 받아갈 수 있어야 한다.
     *
     * <p>이 테스트가 없어서 한 번 놓쳤다. CookieCsrfTokenRepository 는 토큰을 게으르게
     * 만들어서, 아무도 건드리지 않으면 쿠키가 나가지 않는다. 그러면 프론트는 보낼 토큰을
     * 구할 수 없고 모든 쓰기 요청이 영구히 403 이 된다.
     */
    @Test
    void getRequestIssuesCsrfCookieSoTheFrontendCanObtainAToken() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                // 프론트 JS 가 읽어야 하므로 이 쿠키만은 httpOnly 가 아니어야 한다.
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    /**
     * httpOnly 라 프론트가 스스로 못 지운다. 서버가 지워주는 유일한 경로.
     *
     * <p>{@code csrf()} 헬퍼를 쓰지 않는다 — 그 헬퍼는 토큰을 직접 주입해버려서
     * "프론트가 토큰을 구할 수 있는가"라는 진짜 질문을 건너뛴다.
     * 여기서는 쿠키를 실제로 받아서 헤더로 되돌려보낸다.
     */
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

    /** 쿠키 인증이라 CSRF 토큰 없는 POST 는 막혀야 한다. */
    @Test
    void stateChangingRequestWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isForbidden());
    }
}
