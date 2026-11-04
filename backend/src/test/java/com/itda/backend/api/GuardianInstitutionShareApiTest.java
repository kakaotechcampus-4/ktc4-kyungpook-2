package com.itda.backend.api;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;

/**
 * 보호자 기관 공유 내역 (G-45) — 보안 필터와 응답 모양까지 실제로 거친다. 판정의 갈래는 서비스 테스트가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuardianInstitutionShareApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildGuardianRepository childGuardianRepository;
    @Autowired private ChildOrganizationRepository childOrganizationRepository;
    @Autowired private SharingConsentRepository sharingConsentRepository;
    @Autowired private InsightResultRepository insightResultRepository;
    @Autowired private SharingHistoryRepository sharingHistoryRepository;

    private User staff;
    private User parent;
    private Long childId;
    private Long senderId;
    private Long receiverId;
    private Long insightId;

    private static String uniqueKakaoId() {
        return "gs-" + UUID.randomUUID();
    }

    private Cookie auth(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId())));
    }

    private String path() {
        return "/api/v1/guardians/me/children/" + childId + "/institution-shares";
    }

    @BeforeEach
    void setUp() {
        Organization sender = organizationRepository.save(OrganizationFixture.center());
        Organization receiver = organizationRepository.save(OrganizationFixture.center());
        senderId = sender.getId();
        receiverId = receiver.getId();
        staff = userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "이선생", senderId));
        parent = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent(uniqueKakaoId(), "김보호")));
        childId = childRepository.save(Child.of("김하늘", LocalDate.of(2017, 3, 14))).getId();
        childGuardianRepository.save(ChildGuardian.link(childId, parent.getId(), 1L));
        childOrganizationRepository.save(ChildOrganization.of(childId, receiverId, "R-1"));
        sharingConsentRepository.save(
                SharingConsent.grant(childId, parent.getId(), receiverId, List.of(ConsentField.WEEKLY_INSIGHT)));

        insightId = insightResultRepository.save(InsightResult.of(childId, LocalDate.of(2026, 10, 26),
                LocalDate.of(2026, 11, 1), 1, "급식실 입장 직후", "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.",
                null, "[]", "[]", "[%d]".formatted(senderId), false, null)).getId();
        sharingHistoryRepository.save(
                SharingHistory.grant(childId, insightId, senderId, ReceiverType.ORGANIZATION, receiverId));
    }

    @Test
    void 기관별_묶음으로_받은_리포트와_지금_상태를_준다() throws Exception {
        mockMvc.perform(get(path()).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].institution.id").value(String.valueOf(receiverId)))
                .andExpect(jsonPath("$.data[0].shares.length()").value(1))
                .andExpect(jsonPath("$.data[0].shares[0].insightId").value(String.valueOf(insightId)))
                .andExpect(jsonPath("$.data[0].shares[0].period").value("2026년 10월 4주차"))
                .andExpect(jsonPath("$.data[0].shares[0].from.id").value(String.valueOf(senderId)))
                .andExpect(jsonPath("$.data[0].shares[0].grantedAt").isNotEmpty())
                .andExpect(jsonPath("$.data[0].shares[0].visible").value(true))
                .andExpect(jsonPath("$.data[0].shares[0].hiddenAt").value(nullValue()));
    }

    @Test
    void 연결되지_않은_보호자는_403_FORBIDDEN() throws Exception {
        User stranger = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent(uniqueKakaoId(), "남보호")));

        mockMvc.perform(get(path()).cookie(auth(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 기관_담당자는_403_PARENT_REQUIRED() throws Exception {
        mockMvc.perform(get(path()).cookie(auth(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(get(path()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
