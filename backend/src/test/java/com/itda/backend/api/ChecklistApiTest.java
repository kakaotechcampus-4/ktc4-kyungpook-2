package com.itda.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;

/**
 * O-70 · O-71 · O-72 — 보안 필터 · 검증 · 예외 처리까지 실제로 거친다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChecklistApiTest {

    private static final String CHECKLISTS = "/api/v1/institutions/me/checklists";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;

    private User staff;
    private Child sky;

    @BeforeEach
    void setUp() {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        staff = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.organizationUser("cl-api-" + UUID.randomUUID(), organization.getId())));
        sky = childRepository.save(Child.of("하늘", LocalDate.of(2017, 3, 14)));
        childOrganizationRepository.save(ChildOrganization.of(sky.getId(), organization.getId()));
    }

    private Cookie auth(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId())));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, User user) throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return request.cookie(auth(user), csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private ResultActions send(User user, String body) throws Exception {
        return mockMvc.perform(withCsrf(post(CHECKLISTS), user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String body(String items) {
        return "{\"items\": " + items + ", \"childIds\": [\"" + sky.getId() + "\"]}";
    }

    @Test
    void 보내면_201이고_목록에서_보이며_내리면_204로_사라진다() throws Exception {
        String id = JsonPath.read(send(staff, body("[\"  실내화를 새로 준비해주세요.  \"]"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.id").isString())
                        .andExpect(jsonPath("$.data.items[0]").value("실내화를 새로 준비해주세요."))
                        .andExpect(jsonPath("$.data.total").value(1))
                        .andExpect(jsonPath("$.data.confirmed").value(0))
                        .andExpect(jsonPath("$.data.targets[0].child.id").value(String.valueOf(sky.getId())))
                        .andExpect(jsonPath("$.data.targets[0].child.name").value("하늘"))
                        .andExpect(jsonPath("$.data.targets[0].onRoster").value(true))
                        .andExpect(jsonPath("$.data.targets[0].confirmedAt").doesNotExist())
                        .andReturn().getResponse().getContentAsString(),
                "$.data.id");

        mockMvc.perform(get(CHECKLISTS).cookie(auth(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checklists.length()").value(1))
                .andExpect(jsonPath("$.data.checklists[0].id").value(id))
                .andExpect(jsonPath("$.data.checklists[0].sentAt").isString())
                .andExpect(jsonPath("$.data.nextBefore").doesNotExist());

        mockMvc.perform(withCsrf(delete(CHECKLISTS + "/" + id), staff))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(withCsrf(delete(CHECKLISTS + "/" + id), staff))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(CHECKLISTS).cookie(auth(staff)))
                .andExpect(jsonPath("$.data.checklists.length()").value(0));
    }

    /** 항목 0개 · 11개 · 101자 · 공백뿐 · null, 아이 0명 · null 원소, 필드 없음. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"items\": [], \"childIds\": [\"1\"]}",
            "{\"items\": [\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\",\"11\"], \"childIds\": [\"1\"]}",
            "{\"items\": [\"   \"], \"childIds\": [\"1\"]}",
            "{\"items\": [null], \"childIds\": [\"1\"]}",
            "{\"items\": [\"실내화\"], \"childIds\": []}",
            "{\"items\": [\"실내화\"], \"childIds\": [null]}",
            "{}"
    })
    void 형식이_틀리면_400_INVALID_REQUEST다(String body) throws Exception {
        send(staff, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 백한_자는_400이다() throws Exception {
        send(staff, body("[\"" + "가".repeat(101) + "\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 명부에_없는_아이가_섞이면_400_INVALID_CHECKLIST_TARGET이다() throws Exception {
        Child stranger = childRepository.save(Child.of("별", LocalDate.of(2016, 1, 9)));

        send(staff, "{\"items\": [\"실내화\"], \"childIds\": [\"" + sky.getId() + "\", \"" + stranger.getId() + "\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CHECKLIST_TARGET"));
    }

    @Test
    void 보호자는_403_ORGANIZATION_NOT_ASSIGNED다() throws Exception {
        User parent = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.parent("cl-api-parent-" + UUID.randomUUID())));

        send(parent, body("[\"실내화\"]"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
        mockMvc.perform(get(CHECKLISTS).cookie(auth(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
    }

    @Test
    void 없는_요청을_내리면_404다() throws Exception {
        mockMvc.perform(withCsrf(delete(CHECKLISTS + "/999999"), staff))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHECKLIST_NOT_FOUND"));
    }

    @Test
    void 다른_기관_요청을_before_로_주면_400이다() throws Exception {
        mockMvc.perform(get(CHECKLISTS).param("before", "999999").cookie(auth(staff)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
