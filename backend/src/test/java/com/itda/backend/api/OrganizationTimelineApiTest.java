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
 * 기관 아동 기록 타임라인(O-14) — 보안 필터·응답 모양까지 실제로 거친다 (#292). 아이는 기관이 O-11 로 등록하고,
 * 승인본은 저장소로 바로 만든다. 정렬·거르기·수정 표시 규칙은 서비스 테스트가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationTimelineApiTest {

    private static final String CHILDREN = "/api/v1/institutions/me/children";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private SummaryResultRepository summaryResultRepository;
    @Autowired private ChildContextRepository childContextRepository;

    private Organization organization;
    private User staff;
    private User otherStaff;
    private String childId;

    private static String uniqueKakaoId() {
        return "timeline-" + UUID.randomUUID();
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

    private String timelinePath() {
        return CHILDREN + "/" + childId + "/timeline";
    }

    @BeforeEach
    void setUp() throws Exception {
        organization = organizationRepository.save(OrganizationFixture.center());
        staff = userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "이선생", organization.getId()));
        Organization other = organizationRepository.save(OrganizationFixture.center());
        otherStaff = userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "박선생", other.getId()));

        String body = mockMvc.perform(withCsrf(post(CHILDREN), auth(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "김하늘", "birthDate": "2017-03-14", "externalId": "2026-0031"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        childId = JsonPath.read(body, "$.data.child.id");
    }

    @Test
    void 우리_기관이_승인한_요약이_한_건씩_실린다() throws Exception {
        SummaryResult summary = summaryResultRepository.save(SummaryResult.of(Long.valueOf(childId),
                LocalDate.of(2026, 8, 21), organization.getId(), 1, "AI 원문", "[]", "[1, 2]", "[]", "{}"));
        Long contextId = childContextRepository.save(
                ChildContext.of(Long.valueOf(childId), summary.getId(), "승인한 글")).getId();

        mockMvc.perform(get(timelinePath()).cookie(auth(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(String.valueOf(contextId)))
                .andExpect(jsonPath("$.data[0].date").value("2026-08-21"))
                .andExpect(jsonPath("$.data[0].content").value("승인한 글"))
                .andExpect(jsonPath("$.data[0].edited").value(false))
                .andExpect(jsonPath("$.data[0].sourceCount").value(2));
    }

    @Test
    void 승인본이_없으면_빈_배열() throws Exception {
        mockMvc.perform(get(timelinePath()).cookie(auth(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    /** 다른 기관 아이가 있는지조차 알려 주지 않는다. */
    @Test
    void 다른_기관_아이는_404_CHILD_NOT_FOUND() throws Exception {
        mockMvc.perform(get(timelinePath()).cookie(auth(otherStaff)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHILD_NOT_FOUND"));
    }

    @Test
    void 보호자는_403_ORGANIZATION_NOT_ASSIGNED() throws Exception {
        User parent = userRepository.save(UserFixture.parent(uniqueKakaoId(), "김보호"));

        mockMvc.perform(get(timelinePath()).cookie(auth(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(get(timelinePath()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
