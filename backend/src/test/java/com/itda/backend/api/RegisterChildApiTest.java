package com.itda.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.UserRepository;

/**
 * POST /api/v1/institutions/me/children — 보안 필터·검증·저장까지 실제로 거친다.
 *
 * <p>CSRF 는 {@code csrf()} 헬퍼로 주입하지 않고, 프론트처럼 XSRF-TOKEN 쿠키를 받아 헤더로 되돌려보낸다.
 * CSRF 검사는 인증·가입 확인보다 먼저 돌기 때문에, 401·SIGNUP_NOT_COMPLETED·ORGANIZATION_NOT_ASSIGNED 를
 * 확인하는 요청에는 유효한 토큰을 넣는다. CSRF 실패는 그 밖의 조건이 모두 통과하는 기관 계정으로만 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegisterChildApiTest {

    private static final String CHILDREN = "/api/v1/institutions/me/children";
    private static final String BODY = """
            {"name": "김하늘", "birthDate": "2017-03-14", "externalId": "2026-0031"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private static String uniqueKakaoId() {
        return "reg-child-" + UUID.randomUUID();
    }

    private User organizationUser() {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        return userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "이선생", organization.getId()));
    }

    private Cookie authCookie(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId())));
    }

    private Cookie csrfCookie() throws Exception {
        return mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private MockHttpServletRequestBuilder registerWithCsrf(Cookie... cookies) throws Exception {
        return registerWithCsrf(BODY, cookies);
    }

    private MockHttpServletRequestBuilder registerWithCsrf(String body, Cookie... cookies) throws Exception {
        Cookie csrf = csrfCookie();
        Cookie[] all = new Cookie[cookies.length + 1];
        System.arraycopy(cookies, 0, all, 0, cookies.length);
        all[cookies.length] = csrf;
        return post(CHILDREN)
                .cookie(all)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void organizationUserRegistersChildAndSeesItInRoster() throws Exception {
        User user = organizationUser();

        String childId = JsonPath.read(
                mockMvc.perform(registerWithCsrf(authCookie(user)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.result").value("SUCCESS"))
                        .andExpect(jsonPath("$.data.child.id").isString())
                        .andExpect(jsonPath("$.data.child.externalId").value("2026-0031"))
                        .andExpect(jsonPath("$.data.child.status").value("pending_consent"))
                        .andReturn().getResponse().getContentAsString(),
                "$.data.child.id");

        mockMvc.perform(get(CHILDREN).cookie(authCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(childId))
                .andExpect(jsonPath("$.data[0].name").value("김하늘"))
                .andExpect(jsonPath("$.data[0].birthDate").value("2017-03-14"))
                .andExpect(jsonPath("$.data[0].externalId").value("2026-0031"))
                .andExpect(jsonPath("$.data[0].status").value("pending_consent"));
    }

    @Test
    void sameExternalIdInSameOrganizationIsConflict() throws Exception {
        User user = organizationUser();
        mockMvc.perform(registerWithCsrf(authCookie(user))).andExpect(status().isCreated());

        mockMvc.perform(registerWithCsrf("""
                        {"name": "임유진", "birthDate": "2019-11-26", "externalId": "2026-0031"}
                        """, authCookie(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EXTERNAL_ID"));

        mockMvc.perform(get(CHILDREN).cookie(authCookie(user)))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void anonymousRequestWithValidCsrfIsUnauthorized() throws Exception {
        mockMvc.perform(registerWithCsrf())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void pendingUserWithValidCsrfIsForbiddenUntilSignupCompletes() throws Exception {
        User pending = userRepository.save(User.pending(uniqueKakaoId(), "박지현"));

        mockMvc.perform(registerWithCsrf(authCookie(pending)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_NOT_COMPLETED"));
    }

    @Test
    void parentWithValidCsrfIsForbiddenBecauseNoOrganization() throws Exception {
        User parent = userRepository.save(UserFixture.parent(uniqueKakaoId(), "김보호"));

        mockMvc.perform(registerWithCsrf(authCookie(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
    }

    @Test
    void organizationUserWithoutCsrfTokenIsForbidden() throws Exception {
        User user = organizationUser();

        mockMvc.perform(post(CHILDREN)
                        .cookie(authCookie(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
