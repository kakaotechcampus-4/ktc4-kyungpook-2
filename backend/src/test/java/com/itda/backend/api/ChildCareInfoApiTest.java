package com.itda.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChildCareInfoApiTest {

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
    private TermsAgreementRepository termsAgreementRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private User staff;
    private User parent;
    private String childId;

    private static String uniqueKakaoId() {
        return "care-info-" + UUID.randomUUID();
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

    private String carePath() {
        return "/api/v1/children/" + childId + "/care-info";
    }

    private ResultActions save(User user, String body) throws Exception {
        return mockMvc.perform(withCsrf(put(carePath()), auth(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @BeforeEach
    void setUp() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        staff = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), organization.getId())));
        parent = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent(uniqueKakaoId())));

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

    @Test
    void 저장하면_다듬은_값을_돌려주고_아이_목록과_홈에_보인다() throws Exception {
        save(parent, """
                {"allergies": [" 땅콩 ", "갑각류 (새우)", "땅콩"],
                 "medications": [{"name": "○○정", "time": "점심 식후"}, {"name": "△△시럽"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allergies.length()").value(2))
                .andExpect(jsonPath("$.data.allergies[0]").value("땅콩"))
                .andExpect(jsonPath("$.data.allergies[1]").value("갑각류 (새우)"))
                .andExpect(jsonPath("$.data.medications[0].name").value("○○정"))
                .andExpect(jsonPath("$.data.medications[0].time").value("점심 식후"))
                .andExpect(jsonPath("$.data.medications[1].time").value(""));

        mockMvc.perform(get(MY_CHILDREN).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].care.allergies.length()").value(2))
                .andExpect(jsonPath("$.data[0].care.medications.length()").value(2));
        mockMvc.perform(get("/api/v1/guardians/me/home?childId=" + childId).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.child.care.allergies[0]").value("땅콩"));
    }

    @Test
    void 저장하기_전에는_빈_배열이다() throws Exception {
        mockMvc.perform(get(MY_CHILDREN).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].care.allergies").isEmpty())
                .andExpect(jsonPath("$.data[0].care.medications").isEmpty());
    }

    @Test
    void 연결된_다른_보호자가_덮어쓴_값이_보인다() throws Exception {
        save(parent, "{\"allergies\": [\"땅콩\"], \"medications\": []}").andExpect(status().isOk());
        User other = linkAnotherParent();

        mockMvc.perform(get(MY_CHILDREN).cookie(auth(other)))
                .andExpect(jsonPath("$.data[0].care.allergies[0]").value("땅콩"));
        save(other, "{\"allergies\": [], \"medications\": []}").andExpect(status().isOk());
        mockMvc.perform(get(MY_CHILDREN).cookie(auth(parent)))
                .andExpect(jsonPath("$.data[0].care.allergies").isEmpty());
    }

    @Test
    void 서로_다른_알레르기_21개는_400() throws Exception {
        String allergies = IntStream.rangeClosed(1, 21).mapToObj(i -> "\"알레르기" + i + "\"")
                .collect(Collectors.joining(", "));

        save(parent, "{\"allergies\": [%s], \"medications\": []}".formatted(allergies))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 잘못된_값은_400() throws Exception {
        for (String body : new String[] {
                "{\"allergies\": [\"%s\"], \"medications\": []}".formatted("가".repeat(31)),
                "{\"allergies\": [\"   \"], \"medications\": []}",
                "{\"allergies\": null, \"medications\": []}",
                "{\"medications\": []}",
                "{\"allergies\": [], \"medications\": [{\"time\": \"점심\"}]}"}) {
            save(parent, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(withCsrf(put(carePath()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allergies\": [], \"medications\": []}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 기관_담당자는_403_PARENT_REQUIRED() throws Exception {
        save(staff, "{\"allergies\": [], \"medications\": []}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
    }

    @Test
    void 연결되지_않은_아이는_403_FORBIDDEN() throws Exception {
        User stranger = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent(uniqueKakaoId())));

        save(stranger, "{\"allergies\": [\"땅콩\"], \"medications\": []}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void CSRF_토큰이_없으면_403() throws Exception {
        mockMvc.perform(put(carePath()).cookie(auth(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allergies\": [], \"medications\": []}"))
                .andExpect(status().isForbidden());
    }

    private User linkAnotherParent() throws Exception {
        User other = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent(uniqueKakaoId())));
        String body = mockMvc.perform(withCsrf(post(CHILDREN + "/" + childId + "/invitations"), auth(staff)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.data.code");
        mockMvc.perform(withCsrf(post(ACCEPT), auth(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(code)))
                .andExpect(status().isOk());
        return other;
    }
}
