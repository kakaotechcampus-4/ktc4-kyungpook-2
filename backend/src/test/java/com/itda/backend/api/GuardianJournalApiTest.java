package com.itda.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.repository.user.UserRepository;

/**
 * 보호자 일지 목록(G-20)·상세(G-21)와 홈의 최근 공유 활동(G-11) — 보안 필터·응답 모양까지 실제로 거친다.
 * 아이는 기관이 O-11 로 등록하고 보호자가 G-04 로 연결한 상태다. 승인본은 Gate 1 승인이 만든 것과 같은 모양으로
 * 저장소에 바로 넣는다 — 요약은 AI 가 있어야 생겨서다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuardianJournalApiTest {

    private static final String CHILDREN = "/api/v1/institutions/me/children";
    private static final String ACCEPT = "/api/v1/guardians/me/invitations/accept";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private SummaryResultRepository summaryResultRepository;

    @Autowired
    private ChildContextRepository childContextRepository;

    private Organization organization;
    private User staff;
    private User parent;
    private String childId;

    private static String uniqueKakaoId() {
        return "journal-" + UUID.randomUUID();
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

    private String journalPath() {
        return "/api/v1/children/" + childId + "/journal";
    }

    private static String entryPath(Long journalId) {
        return "/api/v1/journal/" + journalId;
    }

    @BeforeEach
    void setUp() throws Exception {
        organization = organizationRepository.save(OrganizationFixture.center());
        staff = userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "이선생", organization.getId()));
        parent = userRepository.save(UserFixture.parent(uniqueKakaoId(), "김보호"));

        String body = mockMvc.perform(withCsrf(post(CHILDREN), auth(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "김하늘", "birthDate": "2017-03-14", "externalId": "2026-0031"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        childId = JsonPath.read(body, "$.data.child.id");
        String code = JsonPath.read(body, "$.data.invitation.code");
        mockMvc.perform(withCsrf(post(ACCEPT), auth(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(code)))
                .andExpect(status().isOk());
    }

    private Long approve(String content) {
        Long child = Long.valueOf(childId);
        SummaryResult summary = summaryResultRepository.save(SummaryResult.of(
                child, LocalDate.of(2026, 8, 21), organization.getId(), 1, content, "[]", "[]", "[]", "{}"));
        return childContextRepository.save(ChildContext.of(child, summary.getId(), content)).getId();
    }

    // --- 정상 흐름 ---

    @Test
    void 일지_목록과_상세는_같은_모양이다() throws Exception {
        Long journalId = approve("오후 미술 활동에 끝까지 참여했어요.");

        mockMvc.perform(get(journalPath()).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(String.valueOf(journalId)))
                .andExpect(jsonPath("$.data[0].childId").value(childId))
                .andExpect(jsonPath("$.data[0].institution.id").value(String.valueOf(organization.getId())))
                .andExpect(jsonPath("$.data[0].institution.type").value("center"))
                .andExpect(jsonPath("$.data[0].institution.verified").value(false))
                .andExpect(jsonPath("$.data[0].date").value("2026-08-21"))
                .andExpect(jsonPath("$.data[0].isNew").value(true))
                .andExpect(jsonPath("$.data[0].content").value("오후 미술 활동에 끝까지 참여했어요."))
                .andExpect(jsonPath("$.data[0].new").doesNotExist());

        mockMvc.perform(get(entryPath(journalId)).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(String.valueOf(journalId)))
                .andExpect(jsonPath("$.data.isNew").value(true))
                .andExpect(jsonPath("$.data.content").value("오후 미술 활동에 끝까지 참여했어요."));
    }

    @Test
    void 승인된_일지가_없으면_빈_배열() throws Exception {
        mockMvc.perform(get(journalPath()).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 홈의_최근_공유_활동에_일지가_한_줄로_실린다() throws Exception {
        Long journalId = approve("센터 일지");

        mockMvc.perform(get("/api/v1/guardians/me/home?childId=" + childId).cookie(auth(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activity.length()").value(1))
                .andExpect(jsonPath("$.data.activity[0].id").value(String.valueOf(journalId)))
                .andExpect(jsonPath("$.data.activity[0].text")
                        .value(organization.getName() + "에서 8월 21일 일지가 도착했어요"))
                .andExpect(jsonPath("$.data.activity[0].at").isString())
                .andExpect(jsonPath("$.data.activity[0].journalId").value(String.valueOf(journalId)));
    }

    // --- 오류 ---

    @Test
    void 로그인하지_않으면_401() throws Exception {
        Long journalId = approve("센터 일지");

        mockMvc.perform(get(journalPath()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(entryPath(journalId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 기관_담당자는_403_PARENT_REQUIRED() throws Exception {
        Long journalId = approve("센터 일지");

        mockMvc.perform(get(journalPath()).cookie(auth(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
        mockMvc.perform(get(entryPath(journalId)).cookie(auth(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARENT_REQUIRED"));
    }

    /** 목록은 아이 id 로 부르는 것이라 홈과 같은 403, 상세는 일지 id 로 찾는 것이라 존재를 숨기는 404 다. */
    @Test
    void 연결되지_않은_보호자는_목록_403_상세_404() throws Exception {
        Long journalId = approve("센터 일지");
        User stranger = userRepository.save(UserFixture.parent(uniqueKakaoId(), "남"));

        mockMvc.perform(get(journalPath()).cookie(auth(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get(entryPath(journalId)).cookie(auth(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOURNAL_NOT_FOUND"));
    }

    @Test
    void 없는_일지는_404() throws Exception {
        mockMvc.perform(get(entryPath(999_999_999L)).cookie(auth(parent)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOURNAL_NOT_FOUND"));
    }
}
