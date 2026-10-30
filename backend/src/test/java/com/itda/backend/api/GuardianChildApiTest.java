package com.itda.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.UserRepository;

/**
 * 보호자 아이 목록(G-10)·홈(G-11)·대기 중 기관 권한 요청(G-40) — 보안 필터·요청 검증·응답 모양까지 실제로 거친다.
 * 아이는 기관이 O-11 로 등록하고, 보호자가 그때 받은 코드로 G-04 연결을 마친 상태에서 시작한다(동의 전).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuardianChildApiTest {

    private static final String CHILDREN = "/api/v1/institutions/me/children";
    private static final String ACCEPT = "/api/v1/guardians/me/invitations/accept";
    private static final String MY_CHILDREN = "/api/v1/guardians/me/children";
    private static final String HOME = "/api/v1/guardians/me/home";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private User staff;
    private User parent;
    private String organizationId;
    private String childId;

    private static String uniqueKakaoId() {
        return "my-children-" + UUID.randomUUID();
    }

    private Cookie auth(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId())));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, Cookie... cookies)
            throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        Cookie[] all = new Cookie[cookies.length + 1];
        System.arraycopy(cookies, 0, all, 0, cookies.length);
        all[cookies.length] = csrf;
        return request.cookie(all).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private String homePath() {
        return HOME + "?childId=" + childId;
    }

    private String pendingPath() {
        return "/api/v1/children/" + childId + "/institution-requests/pending";
    }

    @BeforeEach
    void setUp() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        organizationId = String.valueOf(organization.getId());
        staff = userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "이선생", organization.getId()));
        parent = userRepository.save(UserFixture.parent(uniqueKakaoId(), "김보호"));

        String body = mockMvc.perform(withCsrf(post(CHILDREN), auth(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "김하늘", "birthDate": "2017-03-14", "externalId": "2026-0031"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        childId = JsonPath.read(body, "$.data.child.id");
        String code = JsonPath.read(body, "$.data.invitation.code");
        mockMvc.perform(withCsrf(post(ACCEPT), auth(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(code)))
                .andExpect(status().isOk());
    }

    // --- 정상 흐름 ---

    /** 연결만 하고 동의하기 전에도 목록에 나온다. 그래야 설정 화면에서 동의를 켜거나 연결을 끊을 수 있다. */
    @Test
    void 동의_전_아이도_목록에_나온다() throws Exception {
        mockMvc.perform(get(MY_CHILDREN).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(childId))
                .andExpect(jsonPath("$.data[0].name").value("김하늘"))
                .andExpect(jsonPath("$.data[0].birthDate").value("2017-03-14"))
                .andExpect(jsonPath("$.data[0].status").value("pending_consent"))
                .andExpect(jsonPath("$.data[0].institutions.length()").value(1))
                .andExpect(jsonPath("$.data[0].institutions[0].institution.id").value(organizationId))
                .andExpect(jsonPath("$.data[0].institutions[0].institution.type").value("center"))
                .andExpect(jsonPath("$.data[0].institutions[0].institution.verified").value(false))
                .andExpect(jsonPath("$.data[0].institutions[0].consent").value("not_granted"))
                // 저장하는 곳이 없어 키째 뺀다. doesNotExist() 는 "school": null 이어도 통과해서 쓰지 않는다.
                .andExpect(jsonPath("$.data[0].school").doesNotHaveJsonPath())
                // 돌봄 정보는 늘 싣는다. 저장한 적이 없으면 빈 배열 둘이다 (null 은 프론트 타입이 허용하지 않는다)
                .andExpect(jsonPath("$.data[0].care.allergies").isArray())
                .andExpect(jsonPath("$.data[0].care.allergies").isEmpty())
                .andExpect(jsonPath("$.data[0].care.medications").isArray())
                .andExpect(jsonPath("$.data[0].care.medications").isEmpty());
    }

    @Test
    void 동의하면_목록과_홈에_granted_로_보인다() throws Exception {
        mockMvc.perform(withCsrf(put("/api/v1/children/" + childId + "/consent-scopes/" + organizationId),
                                auth(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"grant\", \"allowedFields\": [\"daily_summary\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(MY_CHILDREN).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("active"))
                .andExpect(jsonPath("$.data[0].institutions[0].consent").value("granted"));

        mockMvc.perform(get(homePath()).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.child.id").value(childId))
                .andExpect(jsonPath("$.data.child.status").value("active"))
                .andExpect(jsonPath("$.data.child.institutions[0].consent").value("granted"))
                .andExpect(jsonPath("$.data.child.school").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.child.care.allergies").isEmpty())
                .andExpect(jsonPath("$.data.child.care.medications").isEmpty())
                .andExpect(jsonPath("$.data.activity").isArray())
                .andExpect(jsonPath("$.data.activity").isEmpty());
    }

    @Test
    void 연결된_아이가_없으면_빈_배열() throws Exception {
        User alone = userRepository.save(UserFixture.parent(uniqueKakaoId(), "혼자"));

        mockMvc.perform(get(MY_CHILDREN).cookie(auth(alone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // --- 대기 중 기관 권한 요청 (G-40) ---

    /** 연결만 하고 동의 전이면 그 기관이 대기 중 요청이다. 동의하면 목록에서 빠진다. */
    @Test
    void 동의_전_기관은_대기_중_요청이고_동의하면_빠진다() throws Exception {
        mockMvc.perform(get(pendingPath()).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].institution.id").value(organizationId))
                .andExpect(jsonPath("$.data[0].institution.type").value("center"))
                .andExpect(jsonPath("$.data[0].institution.verified").value(false));

        mockMvc.perform(withCsrf(put("/api/v1/children/" + childId + "/consent-scopes/" + organizationId),
                                auth(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"grant\", \"allowedFields\": [\"daily_summary\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(pendingPath()).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 대기_중_요청은_보호자만_자기_아이만_본다() throws Exception {
        mockMvc.perform(get(pendingPath()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(pendingPath()).cookie(auth(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));

        User stranger = userRepository.save(UserFixture.parent(uniqueKakaoId(), "남"));
        mockMvc.perform(get(pendingPath()).cookie(auth(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- 요청 검증 ---

    /** 어느 아이를 볼지는 화면이 정한다. 서버가 "첫 번째 아이"를 고르지 않는다. */
    @Test
    void 홈은_childId_가_없으면_400() throws Exception {
        mockMvc.perform(get(HOME).cookie(auth(parent)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 홈은_childId_가_숫자가_아니면_400() throws Exception {
        mockMvc.perform(get(HOME + "?childId=abc").cookie(auth(parent)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // --- 인증·권한 ---

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(get(MY_CHILDREN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(homePath()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 가입을_마치지_않으면_403() throws Exception {
        User pending = userRepository.save(User.pending(uniqueKakaoId(), "가입중"));

        mockMvc.perform(get(MY_CHILDREN).cookie(auth(pending)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_NOT_COMPLETED"));
        mockMvc.perform(get(homePath()).cookie(auth(pending)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_NOT_COMPLETED"));
    }

    @Test
    void 기관_담당자는_403_PARENT_REQUIRED() throws Exception {
        mockMvc.perform(get(MY_CHILDREN).cookie(auth(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
        mockMvc.perform(get(homePath()).cookie(auth(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
    }

    @Test
    void 연결되지_않은_아이의_홈은_403_FORBIDDEN() throws Exception {
        User stranger = userRepository.save(UserFixture.parent(uniqueKakaoId(), "남"));

        mockMvc.perform(get(homePath()).cookie(auth(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
