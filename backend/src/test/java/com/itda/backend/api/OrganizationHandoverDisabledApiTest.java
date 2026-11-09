package com.itda.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import org.springframework.http.MediaType;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationHandover;
import com.itda.backend.domain.organization.OrganizationHandoverStatus;
import com.itda.backend.repository.organization.OrganizationHandoverRepository;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;

@SpringBootTest(properties = "app.withdrawal.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationHandoverDisabledApiTest {

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

    @Test
    void 스위치가_꺼져_있으면_404() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User staff = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.organizationUser("ho-off-" + UUID.randomUUID(), organization.getId())));
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post(OrganizationHandoverApiTest.HANDOVER)
                        .cookie(new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(staff.getId()))), csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WITHDRAWAL_NOT_AVAILABLE"));
    }
    @Test
    void 스위치가_꺼져_있으면_이미_발급된_코드로도_가입하지_못한다() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User staff = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.organizationUser("ho-off-s-" + UUID.randomUUID(), organization.getId())));
        OrganizationHandover live = handoverRepository.save(OrganizationHandover.issue(
                organization.getId(), staff.getId(), "HVEK2345", LocalDateTime.now().plusHours(1)));
        User successor = userRepository.save(User.pending("ho-off-n-" + UUID.randomUUID()));
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .cookie(new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(successor.getId()))), csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"org\", \"handoverCode\": \"%s\"}".formatted(live.getCode())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HANDOVER_CODE"));

        assertThat(handoverRepository.findByCode(live.getCode()).orElseThrow().getStatus())
                .isEqualTo(OrganizationHandoverStatus.PENDING);
        assertThat(userRepository.findById(staff.getId()).orElseThrow().isWithdrawing()).isFalse();
    }
}
