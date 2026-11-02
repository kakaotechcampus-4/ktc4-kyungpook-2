package com.itda.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.repository.user.UserRepository;

/**
 * 기관 수신함 (#273) — 학교가 Gate 2 에서 승인한 글이 센터 수신함에 뜨고, 열면 열람 시각이 남고, 보호자가 센터
 * 동의를 끄면 사라진다. 보안 필터·권한 검사·잠금·저장까지 실제로 거친다.
 *
 * <p>학교가 아이를 등록하고 엄마가 연결해 학교에 동의한다. 센터는 엄마의 기관 코드 요청을 수락하고, 엄마가 센터에도
 * 동의한다. 인사이트는 AI 워커 대신 저장소로 넣고, 승인은 실제 API 로 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InstitutionInboxApiTest {

    private static final String INBOX = "/api/v1/institutions/me/inbox";
    private static final String ROSTER = "/api/v1/institutions/me/children";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private InsightResultRepository insightResultRepository;

    @Autowired
    private SharingHistoryRepository sharingHistoryRepository;

    private Organization school;
    private Organization center;
    private User schoolStaff;
    private User centerStaff;
    private User mom;
    private String childId;
    private Long insightId;

    private static String uniqueKakaoId() {
        return "inbox-" + UUID.randomUUID();
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

    private String json(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    private void consent(Organization organization, String action) throws Exception {
        String body = action.equals("grant")
                ? "{\"action\": \"grant\", \"allowedFields\": [\"daily_summary\", \"weekly_insight\"]}"
                : "{\"action\": \"revoke\"}";
        mockMvc.perform(withCsrf(put("/api/v1/children/" + childId + "/consent-scopes/" + organization.getId()),
                        auth(mom))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @BeforeEach
    void setUp() throws Exception {
        school = organizationRepository.save(
                Organization.of("햇살특수학교", OrganizationType.SCHOOL, OrganizationFixture.nextBusinessNumber()));
        center = organizationRepository.save(OrganizationFixture.center());
        schoolStaff = userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "학교선생", school.getId()));
        centerStaff = userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "센터선생", center.getId()));
        mom = userRepository.save(UserFixture.parent(uniqueKakaoId(), "엄마"));

        childId = json(mockMvc.perform(withCsrf(post(ROSTER), auth(schoolStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "임유진", "birthDate": "2017-03-14", "externalId": "S-1"}
                                """))
                .andExpect(status().isCreated()), "$.data.child.id");
        String invite = json(mockMvc.perform(withCsrf(post(ROSTER + "/" + childId + "/invitations"),
                        auth(schoolStaff)))
                .andExpect(status().isCreated()), "$.data.code");
        mockMvc.perform(withCsrf(post("/api/v1/guardians/me/invitations/accept"), auth(mom))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(invite)))
                .andExpect(status().isOk());
        consent(school, "grant");

        // 엄마가 센터 코드로 요청하고 센터가 관리번호를 넣어 수락한다 — 같은 아이가 두 기관 명부에 오른다.
        String centerCode = json(mockMvc.perform(get("/api/v1/institutions/me").cookie(auth(centerStaff)))
                .andExpect(status().isOk()), "$.data.code");
        mockMvc.perform(withCsrf(post("/api/v1/children/" + childId + "/institutions"), auth(mom))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(centerCode)))
                .andExpect(status().isOk());
        mockMvc.perform(withCsrf(post("/api/v1/institutions/me/link-requests/" + childId + "/accept"),
                        auth(centerStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\": \"C-7\"}"))
                .andExpect(status().isOk());
        consent(center, "grant");

        insightId = insightResultRepository.save(InsightResult.of(Long.valueOf(childId),
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23), 1, "급식실 입장 직후", "소리를 지름",
                "이동 전 5분 예고가 효과적이었습니다.", null, "[]", "[]", "[%d]".formatted(school.getId()),
                false, null)).getId();
        mockMvc.perform(withCsrf(post("/api/v1/insights/" + insightId + "/gate2"), auth(schoolStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"approve\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 승인된_글이_센터_수신함에_뜨고_열면_본문과_열람_시각이_남는다() throws Exception {
        String raw = mockMvc.perform(get(INBOX).cookie(auth(centerStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(String.valueOf(insightId)))
                .andExpect(jsonPath("$.data[0].childId").value(childId))
                .andExpect(jsonPath("$.data[0].childName").value("임유진"))
                .andExpect(jsonPath("$.data[0].externalId").value("C-7"))
                .andExpect(jsonPath("$.data[0].from.id").value(String.valueOf(school.getId())))
                .andExpect(jsonPath("$.data[0].period").value("2026년 8월 3주차"))
                .andExpect(jsonPath("$.data[0].receivedAt").isNotEmpty())
                // 본문은 단건에서만 준다. 같이 받은 다른 기관도 주지 않는다.
                .andExpect(jsonPath("$.data[0].content").doesNotExist())
                .andExpect(jsonPath("$.data[0].sharedWith").doesNotExist())
                .andExpect(jsonPath("$.data[0].readAt").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        // 공통 응답의 NON_NULL 은 중첩 DTO 에 닿지 않는다. 키는 남고 값만 null 이다.
        assertThat(raw).contains("\"readAt\":null");

        mockMvc.perform(get(INBOX + "/" + insightId).cookie(auth(centerStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("이동 전 5분 예고가 효과적이었습니다."))
                .andExpect(jsonPath("$.data.externalId").value("C-7"))
                .andExpect(jsonPath("$.data.readAt").isNotEmpty());

        mockMvc.perform(get(INBOX).cookie(auth(centerStaff)))
                .andExpect(jsonPath("$.data[0].readAt").isNotEmpty());
    }

    @Test
    void 보호자가_센터_동의를_끄면_목록에서_사라지고_단건은_404_다() throws Exception {
        consent(center, "revoke");

        mockMvc.perform(get(INBOX).cookie(auth(centerStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get(INBOX + "/" + insightId).cookie(auth(centerStaff)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("SHARING_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(sharingHistoryRepository
                .findByInsightResultIdAndReceiverTypeAndReceiverId(insightId, ReceiverType.ORGANIZATION, center.getId()))
                .hasValueSatisfying(grant -> assertThat(grant.getReadAt()).isNull());
    }

    /** 승인한 기관 자신은 받지 않는다. 남의 기관에 열린 글은 있는지조차 알려주지 않는다. */
    @Test
    void 승인한_학교의_수신함에는_없고_단건은_404_다() throws Exception {
        mockMvc.perform(get(INBOX).cookie(auth(schoolStaff)))
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get(INBOX + "/" + insightId).cookie(auth(schoolStaff)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHARING_NOT_FOUND"));
    }

    @Test
    void 보호자_계정은_403_이다() throws Exception {
        mockMvc.perform(get(INBOX).cookie(auth(mom)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
        mockMvc.perform(get(INBOX + "/" + insightId).cookie(auth(mom)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 로그인하지_않으면_401_이다() throws Exception {
        mockMvc.perform(get(INBOX))
                .andExpect(status().isUnauthorized());
    }
}
