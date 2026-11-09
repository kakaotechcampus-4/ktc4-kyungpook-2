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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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

/** 탈퇴 스위치가 꺼져 있으면 인계 코드도 발급하지 않는다 — 인계가 끝나면 이전 담당자를 탈퇴시키기 때문이다 (#478). */
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
}
