package com.itda.backend.api;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.repository.user.UserRepository;

/**
 * 보호자 받은 인사이트 목록·단건 — 보안 필터와 응답 모양까지 실제로 거친다. 인사이트·승인 기록·권한 행은 저장소로 바로
 * 만든다. Gate 2 를 지나는 과정은 이 테스트의 관심이 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuardianInsightApiTest {

    private static final String INSIGHTS = "/api/v1/guardians/me/insights";
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 27, 15, 30);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private InsightResultRepository insightResultRepository;
    @Autowired private ApprovalRepository approvalRepository;
    @Autowired private SharingHistoryRepository sharingHistoryRepository;

    private User staff;
    private User parent;
    private Long insightId;

    private static String uniqueKakaoId() {
        return "gi-" + UUID.randomUUID();
    }

    private Cookie auth(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId())));
    }

    @BeforeEach
    void setUp() {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        staff = userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "이선생", organization.getId()));
        parent = userRepository.save(UserFixture.parent(uniqueKakaoId(), "김보호"));
        Long childId = childRepository.save(Child.of("김하늘", LocalDate.of(2017, 3, 14))).getId();

        InsightResult insight = InsightResult.of(childId, LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23), 1,
                "급식실 입장 직후", "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.", null, "[]", "[]",
                "[%d]".formatted(organization.getId()), false, null);
        insight.approveGate2();
        insightId = insightResultRepository.save(insight).getId();
        Approval approval = Approval.gate2(insightId, staff.getId(), ApprovalDecision.APPROVED);
        ReflectionTestUtils.setField(approval, "reviewedAt", APPROVED_AT);
        approvalRepository.save(approval);
        sharingHistoryRepository.save(SharingHistory.grant(childId, insightId, organization.getId(),
                ReceiverType.GUARDIAN, parent.getId()));
    }

    @Test
    void 목록에_승인_시각이_실린다() throws Exception {
        mockMvc.perform(get(INSIGHTS).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(String.valueOf(insightId)))
                .andExpect(jsonPath("$.data[0].period").value("2026년 8월 3주차"))
                .andExpect(jsonPath("$.data[0].approvedAt").value("2026-10-27T15:30:00"))
                .andExpect(jsonPath("$.data[0].readAt").value(nullValue()));
    }

    @Test
    void 단건을_열면_승인_시각과_처음_본_시각이_실린다() throws Exception {
        mockMvc.perform(get(INSIGHTS + "/" + insightId).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approvedAt").value("2026-10-27T15:30:00"))
                .andExpect(jsonPath("$.data.readAt").isNotEmpty());
    }

    @Test
    void 기관_담당자는_403_PARENT_REQUIRED() throws Exception {
        mockMvc.perform(get(INSIGHTS).cookie(auth(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(get(INSIGHTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
