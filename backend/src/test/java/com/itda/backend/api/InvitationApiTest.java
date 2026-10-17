package com.itda.backend.api;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
 * 기관 쪽 초대 코드·보호자 연결 API(O-15~O-19) — 보안 필터·권한 검사·저장까지 실제로 거친다.
 *
 * <p>아이는 O-11 로 실제 등록하고, 보호자 연결은 G-04 로 실제 수락한다. CSRF 는 프론트처럼 XSRF-TOKEN 쿠키를
 * 받아 헤더로 되돌려보낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvitationApiTest {

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
    private User otherStaff;
    private User parent;
    private String childId;
    private String registeredCode;

    private static String uniqueKakaoId() {
        return "invite-api-" + UUID.randomUUID();
    }

    private User organizationUser() {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        return userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "이선생", organization.getId()));
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

    private String invitations() {
        return CHILDREN + "/" + childId + "/invitations";
    }

    private String guardians() {
        return CHILDREN + "/" + childId + "/guardians";
    }

    private void accept(User guardian, String code) throws Exception {
        mockMvc.perform(withCsrf(post(ACCEPT), auth(guardian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(code)))
                .andExpect(status().isOk());
    }

    @BeforeEach
    void setUp() throws Exception {
        staff = organizationUser();
        otherStaff = organizationUser();
        parent = userRepository.save(UserFixture.parent(uniqueKakaoId(), "김보호"));

        String body = mockMvc.perform(withCsrf(post(CHILDREN), auth(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "김하늘", "birthDate": "2017-03-14", "externalId": "2026-0031"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        childId = JsonPath.read(body, "$.data.child.id");
        registeredCode = JsonPath.read(body, "$.data.invitation.code");
    }

    // --- O-15 추가 발급 ---

    @Test
    void 기관은_추가_코드를_발급하고_목록에서_본다() throws Exception {
        String body = mockMvc.perform(withCsrf(post(invitations()), auth(staff)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.code").value(org.hamcrest.Matchers.matchesPattern("[A-Z2-9]{8}")))
                .andExpect(jsonPath("$.data.expiresAt").isString())
                .andReturn().getResponse().getContentAsString();
        String added = JsonPath.read(body, "$.data.code");

        mockMvc.perform(get(invitations()).cookie(auth(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].code").value(hasItem(added)))
                .andExpect(jsonPath("$.data[*].code").value(hasItem(registeredCode)));
    }

    @Test
    void 익명은_401() throws Exception {
        mockMvc.perform(withCsrf(post(invitations())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(guardians()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 보호자는_기관_API를_쓸_수_없다() throws Exception {
        mockMvc.perform(withCsrf(post(invitations()), auth(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
        mockMvc.perform(get(invitations()).cookie(auth(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
        mockMvc.perform(get(guardians()).cookie(auth(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
    }

    @Test
    void CSRF_토큰이_없으면_403() throws Exception {
        mockMvc.perform(post(invitations()).cookie(auth(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(delete(guardians() + "/" + parent.getId()).cookie(auth(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 다른 기관 아이는 있는지조차 알려 주지 않는다 — 발급·조회·취소·보호자 목록·해제 모두 같은 404. */
    @Test
    void 다른_기관_아이는_모든_기관_API에서_404() throws Exception {
        String invitationId = JsonPath.read(mockMvc.perform(get(invitations()).cookie(auth(staff)))
                .andReturn().getResponse().getContentAsString(), "$.data[0].id");
        accept(parent, registeredCode);

        mockMvc.perform(withCsrf(post(invitations()), auth(otherStaff)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHILD_NOT_FOUND"));
        mockMvc.perform(get(invitations()).cookie(auth(otherStaff)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHILD_NOT_FOUND"));
        mockMvc.perform(withCsrf(delete(invitations() + "/" + invitationId), auth(otherStaff)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHILD_NOT_FOUND"));
        mockMvc.perform(get(guardians()).cookie(auth(otherStaff)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHILD_NOT_FOUND"));
        mockMvc.perform(withCsrf(delete(guardians() + "/" + parent.getId()), auth(otherStaff)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHILD_NOT_FOUND"));

        mockMvc.perform(get(guardians()).cookie(auth(staff)))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // --- O-16·O-17 목록·취소 ---

    @Test
    void 취소한_코드는_목록에서_빠지고_수락할_수_없다() throws Exception {
        String invitationId = JsonPath.read(mockMvc.perform(get(invitations()).cookie(auth(staff)))
                .andReturn().getResponse().getContentAsString(), "$.data[0].id");

        mockMvc.perform(withCsrf(delete(invitations() + "/" + invitationId), auth(staff)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(invitations()).cookie(auth(staff)))
                .andExpect(jsonPath("$.data[*].code").value(not(hasItem(registeredCode))));
        mockMvc.perform(withCsrf(delete(invitations() + "/" + invitationId), auth(staff)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
        mockMvc.perform(withCsrf(post(ACCEPT), auth(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(registeredCode)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"));
    }

    // --- O-18·O-19 보호자 목록·해제 ---

    @Test
    void 우리_코드로_연결된_보호자를_보고_해제한다() throws Exception {
        accept(parent, registeredCode);

        mockMvc.perform(get(guardians()).cookie(auth(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].userId").value(String.valueOf(parent.getId())))
                .andExpect(jsonPath("$.data[0].name").value("김보호"))
                .andExpect(jsonPath("$.data[0].linkedAt").isString())
                .andExpect(jsonPath("$.data[0].linkedByThisOrganization").value(true));

        mockMvc.perform(withCsrf(delete(guardians() + "/" + parent.getId()), auth(staff)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(guardians()).cookie(auth(staff)))
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(withCsrf(delete(guardians() + "/" + parent.getId()), auth(staff)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GUARDIAN_NOT_FOUND"));
    }
}
