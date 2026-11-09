package com.itda.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.checklist.ChecklistService;

/**
 * G-50 · G-51 — 보안 필터 · 검증 · 예외 처리까지 실제로 거친다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuardianChecklistApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private ChildGuardianRepository childGuardianRepository;
    @Autowired private ChecklistService checklistService;

    private User staff;
    private User mom;
    private Child sky;
    private String checklistId;

    @BeforeEach
    void setUp() {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        staff = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.organizationUser("gcl-o-" + UUID.randomUUID(),
                organization.getId())));
        mom = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.parent("gcl-m-" + UUID.randomUUID())));
        sky = childRepository.save(Child.of("하늘", LocalDate.of(2017, 3, 14)));
        childOrganizationRepository.save(ChildOrganization.of(sky.getId(), organization.getId()));
        childGuardianRepository.save(ChildGuardian.link(sky.getId(), mom.getId(), 1L));
        checklistId = checklistService.send(String.valueOf(staff.getId()), List.of("실내화를 새로 준비해주세요."),
                List.of(sky.getId())).id();
    }

    private Cookie auth(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId())));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, User user) throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return request.cookie(auth(user), csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private String list() {
        return "/api/v1/children/" + sky.getId() + "/checklists";
    }

    @Test
    void 목록을_보고_확인하면_확인_안_한_목록에서_빠진다() throws Exception {
        mockMvc.perform(get(list()).cookie(auth(mom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(checklistId))
                .andExpect(jsonPath("$.data[0].institution.name").isString())
                .andExpect(jsonPath("$.data[0].items[0]").value("실내화를 새로 준비해주세요."))
                .andExpect(jsonPath("$.data[0].sentAt").isString())
                .andExpect(jsonPath("$.data[0].status").value("needs_check"));
        mockMvc.perform(get(list()).param("status", "needs_check").cookie(auth(mom)))
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(withCsrf(post(list() + "/" + checklistId + "/confirm"), mom))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("confirmed"))
                .andExpect(jsonPath("$.data.confirmedAt").isString())
                .andExpect(jsonPath("$.data.confirmedByName").doesNotExist());

        mockMvc.perform(get(list()).param("status", "needs_check").cookie(auth(mom)))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 모르는_status_는_400이다() throws Exception {
        mockMvc.perform(get(list()).param("status", "confirmed").cookie(auth(mom)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 연결되지_않은_보호자는_403이다() throws Exception {
        User stranger = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.parent("gcl-s-" + UUID.randomUUID())));

        mockMvc.perform(get(list()).cookie(auth(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(withCsrf(post(list() + "/" + checklistId + "/confirm"), stranger))
                .andExpect(status().isForbidden());
    }

    @Test
    void 기관_계정은_403_PARENT_REQUIRED다() throws Exception {
        mockMvc.perform(get(list()).cookie(auth(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
    }

    @Test
    void 내린_요청을_확인하면_404다() throws Exception {
        checklistService.cancel(String.valueOf(staff.getId()), Long.valueOf(checklistId));

        mockMvc.perform(withCsrf(post(list() + "/" + checklistId + "/confirm"), mom))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHECKLIST_NOT_FOUND"));
        mockMvc.perform(get(list()).cookie(auth(mom)))
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
