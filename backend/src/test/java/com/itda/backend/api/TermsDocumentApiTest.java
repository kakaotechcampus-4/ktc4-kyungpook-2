package com.itda.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.domain.user.TermKey;
import com.itda.backend.service.user.TermsCatalog;

/**
 * GET /api/v1/terms — 로그인 없이 보안 필터를 지나 본문까지 온다.
 * 가입 미완료 · 약관 미동의 회원이 읽는 경우는 SignupCompletionFilterTest 가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TermsDocumentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 로그인_없이_보호자_약관_둘을_준다() throws Exception {
        String version = TermsCatalog.currentVersion(TermKey.TOS);

        mockMvc.perform(get("/api/v1/terms").param("role", "parent"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].key").value("tos"))
                .andExpect(jsonPath("$.data[0].title").value("서비스 이용약관"))
                .andExpect(jsonPath("$.data[0].version").value(version))
                .andExpect(jsonPath("$.data[0].effectiveDate").value(version.substring(0, 10)))
                .andExpect(jsonPath("$.data[0].draft").value(true))
                .andExpect(jsonPath("$.data[0].body").isNotEmpty())
                .andExpect(jsonPath("$.data[1].key").value("privacy"));
    }

    @Test
    void 로그인_없이_기관_약관_셋을_준다() throws Exception {
        mockMvc.perform(get("/api/v1/terms").param("role", "org"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].key").value(org.hamcrest.Matchers.contains(
                        "org_tos", "org_privacy", "org_child_data")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "admin", "ORG", "parent,org"})
    void 역할이_parent_org_가_아니면_400이다(String role) throws Exception {
        mockMvc.perform(get("/api/v1/terms").param("role", role))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 역할이_없으면_400이다() throws Exception {
        mockMvc.perform(get("/api/v1/terms"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    /** 공개는 정확히 /api/v1/terms 하나다. 그 아래 경로는 로그인이 필요하다. */
    @Test
    void 약관_본문_아래_경로는_로그인이_필요하다() throws Exception {
        mockMvc.perform(get("/api/v1/terms/tos"))
                .andExpect(status().isUnauthorized());
    }
}
