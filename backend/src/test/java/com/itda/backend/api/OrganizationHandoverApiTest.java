package com.itda.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationHandover;
import com.itda.backend.domain.organization.OrganizationHandoverStatus;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.organization.OrganizationHandoverRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationHandoverApiTest {

    static final String HANDOVER = "/api/v1/institutions/me/handover";

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

    @Autowired
    private OrganizationHandoverRepository handoverRepository;

    private User staff() {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        return TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.organizationUser("ho-" + UUID.randomUUID(), organization.getId())));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, User user) throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return mockMvc.perform(request
                .cookie(new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId()))), csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()));
    }

    @Test
    void 기관_담당자는_12시간_쓸_수_있는_8자_코드를_받는다() throws Exception {
        User staff = staff();
        OffsetDateTime before = OffsetDateTime.now();

        String body = perform(post(HANDOVER), staff)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();

        String code = JsonPath.read(body, "$.data.code");
        OffsetDateTime expiresAt = OffsetDateTime.parse(JsonPath.read(body, "$.data.expiresAt"));
        assertThat(code).hasSize(OrganizationHandover.CODE_LENGTH);
        assertThat(Duration.between(before, expiresAt)).isBetween(Duration.ofHours(12).minusMinutes(1),
                Duration.ofHours(12).plusMinutes(1));

        OrganizationHandover saved = handoverRepository.findByCode(code).orElseThrow();
        assertThat(saved.getOrganizationId()).isEqualTo(staff.getOrganizationId());
        assertThat(saved.getIssuedBy()).isEqualTo(staff.getId());
        assertThat(saved.getStatus()).isEqualTo(OrganizationHandoverStatus.PENDING);
    }

    @Test
    void 다시_받으면_이전_코드는_취소된다() throws Exception {
        User staff = staff();
        String first = JsonPath.read(perform(post(HANDOVER), staff).andReturn().getResponse().getContentAsString(),
                "$.data.code");

        String second = JsonPath.read(perform(post(HANDOVER), staff)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.data.code");

        assertThat(second).isNotEqualTo(first);
        assertThat(handoverRepository.findByCode(first).orElseThrow().getStatus())
                .isEqualTo(OrganizationHandoverStatus.CANCELLED);
        assertThat(handoverRepository.findByCode(second).orElseThrow().getStatus())
                .isEqualTo(OrganizationHandoverStatus.PENDING);
    }

    @Test
    void 취소하면_살아_있는_코드가_없어진다() throws Exception {
        User staff = staff();
        String code = JsonPath.read(perform(post(HANDOVER), staff).andReturn().getResponse().getContentAsString(),
                "$.data.code");

        perform(delete(HANDOVER), staff).andExpect(status().isNoContent());

        assertThat(handoverRepository.findByCode(code).orElseThrow().getStatus())
                .isEqualTo(OrganizationHandoverStatus.CANCELLED);
        perform(delete(HANDOVER), staff).andExpect(status().isNoContent());
    }

    @Test
    void 보호자는_403() throws Exception {
        User parent = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.parent("ho-p-" + UUID.randomUUID())));

        perform(post(HANDOVER), parent)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mockMvc.perform(post(HANDOVER).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
    }
}
