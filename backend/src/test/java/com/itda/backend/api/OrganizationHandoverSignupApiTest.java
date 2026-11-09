package com.itda.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationHandover;
import com.itda.backend.domain.organization.OrganizationHandoverStatus;
import com.itda.backend.domain.user.User;
import com.itda.backend.domain.user.UserRole;
import com.itda.backend.domain.user.WithdrawalStatus;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.organization.OrganizationHandoverRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.user.KakaoUnlinkClient;
import com.itda.backend.service.user.KakaoUnlinkResult;

/**
 * 인계 코드로 가입하면 같은 기관의 담당자가 되고, 코드를 발급한 담당자는 탈퇴로 정리된다 (#478).
 * 탈퇴 실행기는 실제로 돌리고 카카오 호출만 가짜로 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationHandoverSignupApiTest {

    private static final String SIGNUP = "/api/v1/auth/signup";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationHandoverRepository handoverRepository;

    @MockitoBean private KakaoUnlinkClient kakaoUnlinkClient;

    private Organization organization;
    private User staff;

    @BeforeEach
    void setUp() {
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.UNLINKED);
        organization = organizationRepository.save(OrganizationFixture.center());
        // 카카오 회원번호는 숫자다 — 실행기가 숫자로 바꿔 연결 해제를 부른다
        staff = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.organizationUser(kakaoId(), organization.getId())));
    }

    private static String kakaoId() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, User user) throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return mockMvc.perform(request
                .cookie(new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId()))), csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()));
    }

    private String issueCode() throws Exception {
        return JsonPath.read(perform(post(OrganizationHandoverApiTest.HANDOVER), staff)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.data.code");
    }

    private ResultActions signup(User user, String body) throws Exception {
        return perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(body), user);
    }

    private static String handoverBody(String code) {
        return """
                {"role": "org", "handoverCode": "%s"}
                """.formatted(code);
    }

    private User pending() {
        return userRepository.save(User.pending(kakaoId()));
    }

    /** 탈퇴 실행기는 다른 스레드다. 조건이 맞을 때까지 잠깐 기다린다. */
    private User awaitUser(long id, Predicate<User> condition) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            User user = userRepository.findById(id).orElseThrow();
            if (condition.test(user)) {
                return user;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("회원 상태가 기대대로 바뀌지 않았습니다: " + id);
    }

    @Test
    void 코드로_가입하면_같은_기관_담당자가_되고_이전_담당자는_탈퇴된다() throws Exception {
        String code = issueCode();
        User successor = pending();

        // 화면은 XXXX-XXXX 로 보여 주고 사람이 소문자로 칠 수도 있다
        String typed = (code.substring(0, 4) + "-" + code.substring(4)).toLowerCase();
        signup(successor, handoverBody(typed))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("org"))
                .andExpect(jsonPath("$.data.institutionId").value(String.valueOf(organization.getId())))
                .andExpect(jsonPath("$.data.institutionName").value(organization.getName()))
                .andExpect(jsonPath("$.data.termsAgreed").value(false));

        User saved = userRepository.findById(successor.getId()).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(UserRole.ORGANIZATION);
        assertThat(saved.getOrganizationId()).isEqualTo(organization.getId());

        OrganizationHandover handover = handoverRepository.findByCode(code).orElseThrow();
        assertThat(handover.getStatus()).isEqualTo(OrganizationHandoverStatus.ACCEPTED);
        assertThat(handover.getAcceptedUserId()).isEqualTo(successor.getId());

        String formerKakaoId = staff.getKakaoId();
        User former = awaitUser(staff.getId(), User::isDeleted);
        verify(kakaoUnlinkClient).unlink(Long.parseLong(formerKakaoId));
        assertThat(former.getKakaoId()).startsWith("withdrawn:");
        // 기관은 그대로다 — 새 기관이 생기지 않는다
        assertThat(organizationRepository.findById(organization.getId())).isPresent();

        // 이전 담당자의 출입증은 더 이상 쓰지 못한다
        mockMvc.perform(get("/api/v1/auth/me")
                        .cookie(new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(staff.getId())))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_WITHDRAWN"));
    }

    @Test
    void 틀린_코드는_400이고_가입되지_않는다() throws Exception {
        issueCode();
        User successor = pending();

        signup(successor, handoverBody("ZZZZZZZZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HANDOVER_CODE"));
        signup(successor, handoverBody("짧음"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HANDOVER_CODE"));

        assertThat(userRepository.findById(successor.getId()).orElseThrow().getRole()).isNull();
        assertThat(userRepository.findById(staff.getId()).orElseThrow().isWithdrawing()).isFalse();
    }

    @Test
    void 만료된_코드는_400() throws Exception {
        OrganizationHandover expired = handoverRepository.save(OrganizationHandover.issue(
                organization.getId(), staff.getId(), "EXP23456", LocalDateTime.now().minusMinutes(1)));

        signup(pending(), handoverBody(expired.getCode()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HANDOVER_CODE"));
    }

    @Test
    void 이미_쓴_코드와_취소된_코드는_400() throws Exception {
        String used = issueCode();
        signup(pending(), handoverBody(used)).andExpect(status().isCreated());

        signup(pending(), handoverBody(used))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HANDOVER_CODE"));
    }

    @Test
    void 다시_발급해서_취소된_코드는_400() throws Exception {
        String first = issueCode();
        issueCode();

        signup(pending(), handoverBody(first))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HANDOVER_CODE"));
    }

    @Test
    void 코드와_기관_정보를_같이_보내거나_보호자가_코드를_보내면_400() throws Exception {
        String code = issueCode();

        signup(pending(), """
                {"role": "org", "handoverCode": "%s", "organizationName": "햇살아동발달센터",
                 "organizationType": "CENTER", "businessNumber": "%s"}
                """.formatted(code, OrganizationFixture.nextBusinessNumber()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        signup(pending(), """
                {"role": "parent", "handoverCode": "%s"}
                """.formatted(code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(handoverRepository.findByCode(code).orElseThrow().getStatus())
                .isEqualTo(OrganizationHandoverStatus.PENDING);
    }

    /**
     * 카카오 첫 호출이 분명히 실패해도 인계 탈퇴는 취소하지 않는다. 취소하면 이전 담당자가 되살아나 한 기관에 계정이 둘이 된다.
     * 보호자 탈퇴처럼 접수를 되돌리지 않고 간격을 늘려 다시 시도한다.
     */
    @Test
    void 카카오_첫_호출이_실패해도_이전_담당자_탈퇴는_취소하지_않고_다시_시도한다() throws Exception {
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.FAILED);
        String code = issueCode();

        signup(pending(), handoverBody(code)).andExpect(status().isCreated());

        User former = awaitUser(staff.getId(), user -> user.kakaoUnlinkFailureCount() > 0);
        assertThat(former.getWithdrawalStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        assertThat(former.isDeleted()).isFalse();
    }
}
