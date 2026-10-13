package com.itda.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.ResultActions;
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
 * 동의 미리보기(G-02)·부여·철회(G-41) — 보안 필터·요청 검증·저장까지 실제로 거친다.
 * 아이는 기관이 O-11 로 등록하고, 보호자가 그때 받은 코드로 G-04 연결을 마친 상태에서 시작한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsentApiTest {

    private static final String CHILDREN = "/api/v1/institutions/me/children";
    private static final String ACCEPT = "/api/v1/guardians/me/invitations/accept";

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
        return "consent-api-" + UUID.randomUUID();
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

    private String scopesPath() {
        return "/api/v1/children/" + childId + "/consent-scopes/" + organizationId;
    }

    private String previewPath() {
        return "/api/v1/children/" + childId + "/consent-preview?institutionId=" + organizationId;
    }

    private ResultActions putConsent(String body, User user) throws Exception {
        return mockMvc.perform(withCsrf(put(scopesPath()), auth(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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

    /** 미리보기의 allowedFields 를 그대로 보내면 동의가 되고, 기관 명부에도 active 로 보인다. */
    @Test
    void 미리보기를_보고_동의하면_아이가_active_가_된다() throws Exception {
        mockMvc.perform(get(previewPath()).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.child.id").value(childId))
                .andExpect(jsonPath("$.data.child.name").value("김하늘"))
                .andExpect(jsonPath("$.data.child.birthDate").value("2017-03-14"))
                .andExpect(jsonPath("$.data.institution.id").value(organizationId))
                .andExpect(jsonPath("$.data.institution.type").value("center"))
                .andExpect(jsonPath("$.data.institution.verified").value(false))
                .andExpect(jsonPath("$.data.documentUrl").isEmpty())
                .andExpect(jsonPath("$.data.sharedFields[0]").value("관찰 요약"))
                .andExpect(jsonPath("$.data.notSharedFields[0]").value("복지카드 정보"))
                .andExpect(jsonPath("$.data.allowedFields[0]").value("daily_summary"))
                .andExpect(jsonPath("$.data.allowedFields[1]").value("weekly_insight"));

        putConsent("""
                {"action": "grant", "allowedFields": ["daily_summary", "weekly_insight"]}
                """, parent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.childStatus").value("active"))
                .andExpect(jsonPath("$.data.consent").value("granted"))
                .andExpect(jsonPath("$.data.allowedFields[0]").value("daily_summary"))
                .andExpect(jsonPath("$.data.allowedFields[1]").value("weekly_insight"));

        mockMvc.perform(get(CHILDREN).cookie(auth(staff)))
                .andExpect(jsonPath("$.data[0].status").value("active"));
        // 이미 동의한 기관은 동의할 것이 없다.
        mockMvc.perform(get(previewPath()).cookie(auth(parent)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONSENT_TARGET_NOT_FOUND"));
    }

    /** revoke 는 allowedFields 를 생략해도 된다. 철회하면 아이는 suspended 가 된다. */
    @Test
    void 동의를_철회하면_suspended_가_된다() throws Exception {
        putConsent("{\"action\": \"grant\", \"allowedFields\": [\"daily_summary\"]}", parent).andExpect(status().isOk());

        putConsent("{\"action\": \"revoke\"}", parent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.childStatus").value("suspended"))
                .andExpect(jsonPath("$.data.consent").value("revoked"))
                .andExpect(jsonPath("$.data.allowedFields").isEmpty());
    }

    @Test
    void 끌_동의가_없는_철회는_200_으로_현재_상태를_돌려준다() throws Exception {
        putConsent("{\"action\": \"revoke\", \"allowedFields\": []}", parent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.childStatus").value("pending_consent"))
                .andExpect(jsonPath("$.data.consent").value("not_granted"));
    }

    /** 연결을 끊으면 그 보호자의 동의도 꺼진다 — 끊긴 사람의 동의로 공유가 계속되면 안 된다. */
    @Test
    void 연결을_끊으면_동의도_꺼진다() throws Exception {
        putConsent("{\"action\": \"grant\", \"allowedFields\": [\"daily_summary\"]}", parent).andExpect(status().isOk());

        mockMvc.perform(withCsrf(delete("/api/v1/guardians/me/children/" + childId), auth(parent)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(CHILDREN).cookie(auth(staff)))
                .andExpect(jsonPath("$.data[0].status").value("suspended"));
    }

    // --- 요청 검증 ---

    @Test
    void grant_에서_항목이_없거나_비었거나_null_이_섞이면_400() throws Exception {
        for (String body : new String[] {
                "{\"action\": \"grant\"}",
                "{\"action\": \"grant\", \"allowedFields\": null}",
                "{\"action\": \"grant\", \"allowedFields\": []}",
                "{\"action\": \"grant\", \"allowedFields\": [\"daily_summary\", null]}",
                "{\"action\": \"grant\", \"allowedFields\": [\"photos\"]}",
                "{\"action\": \"grant\", \"allowedFields\": [\"출결 시간\"]}",
                "{\"allowedFields\": [\"daily_summary\"]}",
                "{\"action\": \"approve\", \"allowedFields\": [\"daily_summary\"]}"}) {
            putConsent(body, parent)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mockMvc.perform(get(CHILDREN).cookie(auth(staff)))
                .andExpect(jsonPath("$.data[0].status").value("pending_consent"));
    }

    /** 요청 필드 이름은 명세대로 camelCase 다. snake_case 로 보내면 항목이 없는 grant 로 읽혀 400 이다. */
    @Test
    void snake_case_필드는_받지_않는다() throws Exception {
        putConsent("{\"action\": \"grant\", \"allowed_fields\": [\"daily_summary\"]}", parent)
                .andExpect(status().isBadRequest());
    }

    @Test
    void 미리보기는_institutionId_가_필요하다() throws Exception {
        mockMvc.perform(get("/api/v1/children/" + childId + "/consent-preview").cookie(auth(parent)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // --- 인증·권한 ---

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(get(previewPath()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 가입을_마치지_않으면_403() throws Exception {
        User pending = userRepository.save(User.pending(uniqueKakaoId(), "가입중"));

        mockMvc.perform(get(previewPath()).cookie(auth(pending)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_NOT_COMPLETED"));
    }

    @Test
    void 기관_담당자는_403_PARENT_REQUIRED() throws Exception {
        mockMvc.perform(get(previewPath()).cookie(auth(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
        putConsent("{\"action\": \"grant\", \"allowedFields\": [\"daily_summary\"]}", staff)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
    }

    @Test
    void 연결되지_않은_아이면_403_FORBIDDEN() throws Exception {
        User stranger = userRepository.save(UserFixture.parent(uniqueKakaoId(), "남"));

        mockMvc.perform(get(previewPath()).cookie(auth(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        putConsent("{\"action\": \"grant\", \"allowedFields\": [\"daily_summary\"]}", stranger)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void CSRF_토큰이_없으면_403() throws Exception {
        mockMvc.perform(put(scopesPath()).cookie(auth(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"grant\", \"allowedFields\": [\"daily_summary\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 명부에_없는_기관이면_404() throws Exception {
        Organization outside = organizationRepository.save(OrganizationFixture.center());

        mockMvc.perform(withCsrf(put("/api/v1/children/" + childId + "/consent-scopes/" + outside.getId()), auth(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"grant\", \"allowedFields\": [\"daily_summary\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONSENT_TARGET_NOT_FOUND"));
    }
}
