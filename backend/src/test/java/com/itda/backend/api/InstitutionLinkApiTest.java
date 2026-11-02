package com.itda.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.child.ChildOrganizationRequest;
import com.itda.backend.domain.child.ChildOrganizationRequestStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildOrganizationRequestRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.UserRepository;

/**
 * 같은 아이에 다른 기관 연결 — 보호자가 기관 코드로 요청하고(G-43·G-44), 기관이 수락·거절한다(O-60~O-62).
 *
 * <p>학교가 아이를 등록하고 엄마가 초대 코드로 연결해 학교에 동의한 상태에서 시작한다. 센터는 아이를 따로 등록하지 않는다.
 * 보안 필터·권한 검사·잠금·저장까지 실제로 거친다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InstitutionLinkApiTest {

    private static final String LOOKUP = "/api/v1/institutions/lookup";
    private static final String LINK_REQUESTS = "/api/v1/institutions/me/link-requests";
    private static final String ROSTER = "/api/v1/institutions/me/children";
    private static final String MY_CHILDREN = "/api/v1/guardians/me/children";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private ChildRepository childRepository;

    @Autowired
    private ChildOrganizationRepository childOrganizationRepository;

    @Autowired
    private ChildOrganizationRequestRepository requestRepository;

    private Organization school;
    private Organization center;
    private User schoolStaff;
    private User centerStaff;
    private User mom;
    private User dad;
    private String childId;
    private String centerCode;
    private String schoolCode;

    private static String uniqueKakaoId() {
        return "link-" + UUID.randomUUID();
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

    private String institutionCodeOf(User staff) throws Exception {
        return json(mockMvc.perform(get("/api/v1/institutions/me").cookie(auth(staff))).andExpect(status().isOk()),
                "$.data.code");
    }

    private ResultActions requestLink(User guardian, String code) throws Exception {
        return mockMvc.perform(withCsrf(post("/api/v1/children/" + childId + "/institutions"), auth(guardian))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted(code)));
    }

    private ResultActions accept(User staff, String externalId) throws Exception {
        return mockMvc.perform(withCsrf(post(LINK_REQUESTS + "/" + childId + "/accept"), auth(staff))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalId\": \"%s\"}".formatted(externalId)));
    }

    private ResultActions reject(User staff) throws Exception {
        return mockMvc.perform(withCsrf(post(LINK_REQUESTS + "/" + childId + "/reject"), auth(staff)));
    }

    private void grant(User guardian, Organization organization) throws Exception {
        mockMvc.perform(withCsrf(put("/api/v1/children/" + childId + "/consent-scopes/" + organization.getId()),
                        auth(guardian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"grant\", \"allowedFields\": [\"daily_summary\"]}"))
                .andExpect(status().isOk());
    }

    private void linkGuardian(User guardian) throws Exception {
        String invite = json(mockMvc.perform(withCsrf(post(ROSTER + "/" + childId + "/invitations"), auth(schoolStaff)))
                .andExpect(status().isCreated()), "$.data.code");
        mockMvc.perform(withCsrf(post("/api/v1/guardians/me/invitations/accept"), auth(guardian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(invite)))
                .andExpect(status().isOk());
    }

    private List<ChildOrganizationRequest> requestsOf(Organization organization) {
        return requestRepository.findAll().stream()
                .filter(request -> request.getChildId().equals(Long.valueOf(childId))
                        && request.getOrganizationId().equals(organization.getId()))
                .toList();
    }

    @BeforeEach
    void setUp() throws Exception {
        school = organizationRepository.save(
                Organization.of("햇살특수학교", OrganizationType.SCHOOL, OrganizationFixture.nextBusinessNumber()));
        center = organizationRepository.save(OrganizationFixture.center());
        schoolStaff = userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "학교선생", school.getId()));
        centerStaff = userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "센터선생", center.getId()));
        mom = userRepository.save(UserFixture.parent(uniqueKakaoId(), "엄마"));
        dad = userRepository.save(UserFixture.parent(uniqueKakaoId(), "아빠"));

        childId = json(mockMvc.perform(withCsrf(post(ROSTER), auth(schoolStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "임유진", "birthDate": "2017-03-14", "externalId": "S-1"}
                                """))
                .andExpect(status().isCreated()), "$.data.child.id");
        linkGuardian(mom);
        grant(mom, school);
        centerCode = institutionCodeOf(centerStaff);
        schoolCode = institutionCodeOf(schoolStaff);
    }

    // --- 기관 코드 조회 ---

    @Test
    void 기관_코드로_기관을_찾는다_하이픈과_소문자도_받는다() throws Exception {
        String typed = (centerCode.substring(0, 4) + "-" + centerCode.substring(4)).toLowerCase();

        mockMvc.perform(get(LOOKUP).param("code", typed).cookie(auth(mom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(String.valueOf(center.getId())))
                .andExpect(jsonPath("$.data.name").value("햇살아동발달센터"))
                .andExpect(jsonPath("$.data.type").value("center"));
        assertThat(requestRepository.findAll()).noneMatch(request -> request.getOrganizationId().equals(center.getId()));
    }

    /** 공통 응답은 null 필드를 빼므로 "data: null" 대신 404 다. 형식이 틀린 코드와 없는 코드를 나누지 않는다. */
    @Test
    void 없는_코드와_형식이_틀린_코드는_404_이고_data_가_없다() throws Exception {
        for (String code : List.of("ZZZZZZZZ", "12")) {
            mockMvc.perform(get(LOOKUP).param("code", code).cookie(auth(mom)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.result").value("FAIL"))
                    .andExpect(jsonPath("$.code").value("INSTITUTION_CODE_NOT_FOUND"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Test
    void 기관_담당자는_기관_코드를_조회할_수_없다() throws Exception {
        mockMvc.perform(get(LOOKUP).param("code", centerCode).cookie(auth(schoolStaff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
    }

    // --- 보호자의 연결 요청 ---

    @Test
    void 연결을_요청하면_수락_대기_중으로_보이고_같은_보호자의_재요청은_새로_만들지_않는다() throws Exception {
        String requestedAt = json(requestLink(mom, centerCode)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.institution.id").value(String.valueOf(center.getId())))
                .andExpect(jsonPath("$.data.status").value("pending")), "$.data.requestedAt");

        requestLink(mom, centerCode)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedAt").value(requestedAt));
        assertThat(requestsOf(center)).hasSize(1);

        mockMvc.perform(get(MY_CHILDREN).cookie(auth(mom)))
                .andExpect(jsonPath("$.data[0].linkRequests.length()").value(1))
                .andExpect(jsonPath("$.data[0].linkRequests[0].institution.id").value(String.valueOf(center.getId())))
                // 아직 명부에 없어 동의할 수 없는 기관이라 기관 목록에는 섞지 않는다.
                .andExpect(jsonPath("$.data[0].institutions.length()").value(1));
    }

    @Test
    void 다른_보호자의_요청은_따로_남고_아이의_보호자_모두에게_보인다() throws Exception {
        linkGuardian(dad);
        requestLink(mom, centerCode).andExpect(status().isOk());
        requestLink(dad, centerCode).andExpect(status().isOk());

        assertThat(requestsOf(center)).hasSize(2).allMatch(ChildOrganizationRequest::isPending);
        mockMvc.perform(get("/api/v1/guardians/me/home").param("childId", childId).cookie(auth(dad)))
                .andExpect(jsonPath("$.data.child.linkRequests.length()").value(1));
    }

    @Test
    void 연결되지_않은_아이는_403() throws Exception {
        requestLink(dad, centerCode)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 이미_명부에_있는_기관은_409_없는_코드는_404() throws Exception {
        requestLink(mom, schoolCode)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSTITUTION_ALREADY_LINKED"));
        requestLink(mom, "ZZZZZZZZ")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INSTITUTION_CODE_NOT_FOUND"));
    }

    // --- 기관의 요청 목록·수락·거절 ---

    @Test
    void 기관은_아이_단위로_묶인_요청을_본다() throws Exception {
        linkGuardian(dad);
        requestLink(mom, centerCode);
        requestLink(dad, centerCode);

        mockMvc.perform(get(LINK_REQUESTS).cookie(auth(centerStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].child.id").value(childId))
                .andExpect(jsonPath("$.data[0].child.name").value("임유진"))
                .andExpect(jsonPath("$.data[0].child.birthDate").value("2017-03-14"))
                .andExpect(jsonPath("$.data[0].requesters[0].name").value("엄마"))
                .andExpect(jsonPath("$.data[0].requesters[1].name").value("아빠"));
        mockMvc.perform(get(LINK_REQUESTS).cookie(auth(schoolStaff)))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /**
     * 수락하면 아이는 하나로 남은 채 센터 명부에 오른다. 학교에 동의해 아이 전체 상태는 active 지만, 센터에는 아직 동의하지
     * 않았으니 센터 명부의 consent 는 not_granted 이고 매칭 대상이 아니다. 보호자에게는 새 기관 권한 요청으로 뜬다.
     */
    @Test
    void 수락하면_센터_명부에_오르고_동의_전이라_매칭_대상이_아니다() throws Exception {
        linkGuardian(dad);
        requestLink(mom, centerCode);
        requestLink(dad, centerCode);

        accept(centerStaff, " C-7 ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(childId))
                .andExpect(jsonPath("$.data.externalId").value("C-7"))
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.consent").value("not_granted"));

        assertThat(requestsOf(center)).hasSize(2)
                .allMatch(request -> request.getStatus() == ChildOrganizationRequestStatus.ACCEPTED);
        mockMvc.perform(get(ROSTER).cookie(auth(centerStaff)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].consent").value("not_granted"));
        mockMvc.perform(get(ROSTER).cookie(auth(schoolStaff)))
                .andExpect(jsonPath("$.data[0].consent").value("granted"));
        assertThat(childRepository.findConsentedByOrganizationId(center.getId()))
                .extracting(Child::getId).doesNotContain(Long.valueOf(childId));

        mockMvc.perform(get("/api/v1/children/" + childId + "/institution-requests/pending").cookie(auth(mom)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].institution.id").value(String.valueOf(center.getId())));
        mockMvc.perform(get(MY_CHILDREN).cookie(auth(mom)))
                .andExpect(jsonPath("$.data[0].institutions.length()").value(2))
                .andExpect(jsonPath("$.data[0].linkRequests.length()").value(0));

        grant(mom, center);
        assertThat(childRepository.findConsentedByOrganizationId(center.getId()))
                .extracting(Child::getId).contains(Long.valueOf(childId));
    }

    @Test
    void 관리번호가_겹치면_409_이고_요청은_그대로_남는다() throws Exception {
        mockMvc.perform(withCsrf(post(ROSTER), auth(centerStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "김하늘", "birthDate": "2018-01-01", "externalId": "C-1"}
                                """))
                .andExpect(status().isCreated());
        requestLink(mom, centerCode);

        accept(centerStaff, "C-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EXTERNAL_ID"));
        assertThat(requestsOf(center)).singleElement().matches(ChildOrganizationRequest::isPending);
    }

    @Test
    void 관리번호가_없으면_400() throws Exception {
        requestLink(mom, centerCode);

        accept(centerStaff, "  ").andExpect(status().isBadRequest());
    }

    @Test
    void 명부에서_빠졌던_아이는_같은_행을_새_관리번호로_되살린다() throws Exception {
        ChildOrganization old = childOrganizationRepository.save(ChildOrganization.of(Long.valueOf(childId), center.getId(), "OLD"));
        old.delete();
        childOrganizationRepository.save(old);
        requestLink(mom, centerCode).andExpect(status().isOk());

        accept(centerStaff, "C-9").andExpect(status().isOk());

        ChildOrganization restored = childOrganizationRepository
                .findByChildIdAndOrganizationId(Long.valueOf(childId), center.getId()).orElseThrow();
        assertThat(restored.getId()).isEqualTo(old.getId());
        assertThat(restored.isDeleted()).isFalse();
        assertThat(restored.getExternalId()).isEqualTo("C-9");
    }

    @Test
    void 요청이_없는_아이는_수락도_거절도_404() throws Exception {
        accept(centerStaff, "C-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_REQUEST_NOT_FOUND"));
        reject(centerStaff)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_REQUEST_NOT_FOUND"));
    }

    @Test
    void 다른_기관에_낸_요청은_수락할_수_없다() throws Exception {
        Organization other = organizationRepository.save(OrganizationFixture.center());
        User otherStaff = userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "다른선생", other.getId()));
        requestLink(mom, centerCode);

        accept(otherStaff, "X-1").andExpect(status().isNotFound());
    }

    @Test
    void 요청_후_다른_경로로_명부에_올랐으면_수락은_409() throws Exception {
        requestLink(mom, centerCode);
        childOrganizationRepository.save(ChildOrganization.of(Long.valueOf(childId), center.getId(), "DIRECT"));

        accept(centerStaff, "C-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSTITUTION_ALREADY_LINKED"));
    }

    @Test
    void 거절하면_목록에서_사라지고_보호자는_다시_요청할_수_있다() throws Exception {
        requestLink(mom, centerCode);

        reject(centerStaff).andExpect(status().isNoContent());

        mockMvc.perform(get(LINK_REQUESTS).cookie(auth(centerStaff))).andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get(MY_CHILDREN).cookie(auth(mom))).andExpect(jsonPath("$.data[0].linkRequests.length()").value(0));
        accept(centerStaff, "C-3").andExpect(status().isNotFound());

        requestLink(mom, centerCode).andExpect(status().isOk());
        assertThat(requestsOf(center)).extracting(ChildOrganizationRequest::getStatus)
                .containsExactlyInAnyOrder(ChildOrganizationRequestStatus.REJECTED, ChildOrganizationRequestStatus.PENDING);
    }

    // --- 보호자 연결 해제 ---

    /** 엄마가 연결을 끊으면 엄마의 요청만 거둔다. 아빠의 요청은 남아 기관이 그대로 수락할 수 있다. */
    @Test
    void 보호자가_연결을_끊으면_그_보호자의_요청만_취소된다() throws Exception {
        linkGuardian(dad);
        requestLink(mom, centerCode);
        requestLink(dad, centerCode);

        mockMvc.perform(withCsrf(delete(MY_CHILDREN + "/" + childId), auth(mom))).andExpect(status().isNoContent());

        assertThat(requestsOf(center)).extracting(ChildOrganizationRequest::getRequestedBy, ChildOrganizationRequest::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(mom.getId(), ChildOrganizationRequestStatus.CANCELLED),
                        org.assertj.core.groups.Tuple.tuple(dad.getId(), ChildOrganizationRequestStatus.PENDING));
        mockMvc.perform(get(LINK_REQUESTS).cookie(auth(centerStaff)))
                .andExpect(jsonPath("$.data[0].requesters.length()").value(1))
                .andExpect(jsonPath("$.data[0].requesters[0].name").value("아빠"));
        accept(centerStaff, "C-4").andExpect(status().isOk());
    }

    @Test
    void 유일한_요청자가_연결을_끊으면_수락할_요청이_없다() throws Exception {
        requestLink(mom, centerCode);

        mockMvc.perform(withCsrf(delete(MY_CHILDREN + "/" + childId), auth(mom))).andExpect(status().isNoContent());

        accept(centerStaff, "C-5").andExpect(status().isNotFound());
        assertThat(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(
                Long.valueOf(childId), center.getId())).isFalse();
    }
}
