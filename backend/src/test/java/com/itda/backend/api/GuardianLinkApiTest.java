package com.itda.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.ResultActions;
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
 * 보호자 쪽 API(G-04 수락, G-05 본인 연결 해제) — 보안 필터·권한 검사·저장까지 실제로 거친다.
 * 초대 코드는 기관이 O-11 로 아이를 등록할 때 받은 실제 코드를 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuardianLinkApiTest {

    private static final String CHILDREN = "/api/v1/institutions/me/children";
    private static final String ACCEPT = "/api/v1/guardians/me/invitations/accept";
    private static final String MY_CHILDREN = "/api/v1/guardians/me/children";

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
    private String childId;
    private String code;

    private static String uniqueKakaoId() {
        return "guardian-api-" + UUID.randomUUID();
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

    private ResultActions accept(String code, Cookie... cookies) throws Exception {
        return mockMvc.perform(withCsrf(post(ACCEPT), cookies)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted(code)));
    }

    private String issueAnotherCode() throws Exception {
        return JsonPath.read(mockMvc.perform(withCsrf(post(CHILDREN + "/" + childId + "/invitations"), auth(staff)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.data.code");
    }

    @BeforeEach
    void setUp() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
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
        code = JsonPath.read(body, "$.data.invitation.code");
    }

    // --- G-04 수락 ---

    /** 화면에 보이는 XXXX-XXXX 를 소문자로 옮겨 적어도 받는다. 연결은 동의가 아니라 아이는 동의 대기 그대로다. */
    @Test
    void 보호자는_코드로_아이와_연결된다() throws Exception {
        String typed = (code.substring(0, 4) + "-" + code.substring(4)).toLowerCase();

        accept(typed, auth(parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.childId").value(childId))
                .andExpect(jsonPath("$.data.childName").value("김하늘"))
                .andExpect(jsonPath("$.data.organizationName").value("햇살아동발달센터"))
                .andExpect(jsonPath("$.data.childStatus").value("pending_consent"));

        mockMvc.perform(get(CHILDREN).cookie(auth(staff)))
                .andExpect(jsonPath("$.data[0].status").value("pending_consent"));
    }

    @Test
    void 한_번_쓴_코드는_400() throws Exception {
        accept(code, auth(parent)).andExpect(status().isOk());
        User otherParent = userRepository.save(UserFixture.parent(uniqueKakaoId(), "이보호"));

        accept(code, auth(otherParent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"));
    }

    @Test
    void 없는_코드와_형식이_틀린_코드는_같은_400() throws Exception {
        accept("ZZZZ-ZZZZ", auth(parent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"));
        accept("1234", auth(parent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"));
    }

    @Test
    void 코드가_비어_있으면_입력_오류() throws Exception {
        accept(" ", auth(parent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 이미_연결된_아이는_409이고_새_코드는_남는다() throws Exception {
        accept(code, auth(parent)).andExpect(status().isOk());
        String another = issueAnotherCode();

        accept(another, auth(parent))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LINK_ALREADY_EXISTS"));

        mockMvc.perform(get(CHILDREN + "/" + childId + "/invitations").cookie(auth(staff)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value(another));
    }

    @Test
    void 익명은_401() throws Exception {
        accept(code)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(withCsrf(delete(MY_CHILDREN + "/" + childId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 기관_담당자는_코드를_쓸_수_없고_코드는_남는다() throws Exception {
        accept(code, auth(staff))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));

        accept(code, auth(parent)).andExpect(status().isOk());
    }

    @Test
    void 가입을_마치지_않은_회원은_코드를_쓸_수_없다() throws Exception {
        User pending = userRepository.save(User.pending(uniqueKakaoId(), "박지현"));

        accept(code, auth(pending))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_NOT_COMPLETED"));
    }

    @Test
    void CSRF_토큰이_없으면_403() throws Exception {
        mockMvc.perform(post(ACCEPT).cookie(auth(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(code)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(delete(MY_CHILDREN + "/" + childId).cookie(auth(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- G-05 본인 해제 ---

    @Test
    void 보호자는_연결을_끊고_새_코드로_다시_연결할_수_있다() throws Exception {
        accept(code, auth(parent)).andExpect(status().isOk());

        mockMvc.perform(withCsrf(delete(MY_CHILDREN + "/" + childId), auth(parent)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(CHILDREN + "/" + childId + "/guardians").cookie(auth(staff)))
                .andExpect(jsonPath("$.data.length()").value(0));

        accept(issueAnotherCode(), auth(parent)).andExpect(status().isOk());
        mockMvc.perform(get(CHILDREN + "/" + childId + "/guardians").cookie(auth(staff)))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void 연결되지_않은_아이는_403() throws Exception {
        mockMvc.perform(withCsrf(delete(MY_CHILDREN + "/" + childId), auth(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 기관_담당자는_보호자_해제_API를_쓸_수_없다() throws Exception {
        mockMvc.perform(withCsrf(delete(MY_CHILDREN + "/" + childId), auth(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
    }
}
