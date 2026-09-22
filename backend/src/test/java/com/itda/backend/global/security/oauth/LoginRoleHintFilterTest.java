package com.itda.backend.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.itda.backend.domain.UserRole;

/**
 * 역할 힌트가 실제 필터 체인에서, 인가 요청이 만들어지기 전에 세션에 들어가는지 확인한다.
 * 필터 등록 위치가 잘못되면(302 를 내보내는 필터 뒤에 두면) 여기가 먼저 깨진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginRoleHintFilterTest {

    private static final String LOGIN_ENTRY = "/oauth2/authorization/kakao";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void inviteParameterMarksTheLoginAsParent() throws Exception {
        MvcResult result = mockMvc.perform(get(LOGIN_ENTRY).param(LoginRoleHintFilter.INVITE_PARAM, "ITDA-1234"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        HttpSession session = result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(LoginRoleHintFilter.ROLE_SESSION_ATTRIBUTE))
                .isEqualTo(UserRole.PARENT);
    }

    /** 초대 코드 없이 들어오면 힌트가 남지 않는다 — 성공 핸들러가 기관으로 처리한다. */
    @Test
    void plainLoginLeavesNoRoleHint() throws Exception {
        MvcResult result = mockMvc.perform(get(LOGIN_ENTRY))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        HttpSession session = result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(LoginRoleHintFilter.ROLE_SESSION_ATTRIBUTE)).isNull();
    }

    /** 중간에 그만둔 보호자 로그인의 흔적이 다음 기관 로그인에 묻어나면 안 된다. */
    @Test
    void staleHintIsClearedWhenEnteringWithoutInvite() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginRoleHintFilter.ROLE_SESSION_ATTRIBUTE, UserRole.PARENT);

        mockMvc.perform(get(LOGIN_ENTRY).session(session))
                .andExpect(status().is3xxRedirection());

        assertThat(session.getAttribute(LoginRoleHintFilter.ROLE_SESSION_ATTRIBUTE)).isNull();
    }

    /** 초대 코드를 붙여도 state 검증은 그대로 걸려 있어야 한다. */
    @Test
    void inviteParameterDoesNotBreakTheAuthorizationRequest() throws Exception {
        mockMvc.perform(get(LOGIN_ENTRY).param(LoginRoleHintFilter.INVITE_PARAM, "ITDA-1234"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Location", org.hamcrest.Matchers.containsString("state=")));
    }
}
