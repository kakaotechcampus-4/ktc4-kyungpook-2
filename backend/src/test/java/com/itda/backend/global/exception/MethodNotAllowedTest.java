package com.itda.backend.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 허용하지 않는 메서드로 부르면 405 여야 한다.
 *
 * <p>GlobalExceptionHandler 의 {@code @ExceptionHandler(Exception.class)} catch-all 이
 * HttpRequestMethodNotSupportedException 까지 삼켜서 500 이 나가고 있었다.
 * 배포 서버에서 {@code GET /api/v1/auth/logout} 으로 확인된 버그다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MethodNotAllowedTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getOnLogoutReturnsMethodNotAllowedInsteadOfServerError() throws Exception {
        mockMvc.perform(get("/api/v1/auth/logout"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    /** 405 응답에는 어떤 메서드가 되는지 알려주는 Allow 헤더가 따라가야 한다. */
    @Test
    void methodNotAllowedResponseCarriesAllowHeader() throws Exception {
        mockMvc.perform(get("/api/v1/auth/logout"))
                .andExpect(header().string("Allow", Matchers.containsString("POST")));
    }

    /**
     * 쓰기 메서드는 405 까지 가지 못한다. CsrfFilter 가 DispatcherServlet 보다 앞에 있어
     * CSRF 토큰 없는 PUT 은 그 자리에서 403 으로 끝난다. 405 는 GET 처럼 CSRF 검사를
     * 거치지 않는 메서드에서만 관측된다 — 이 차이를 알아두려고 남긴다.
     */
    @Test
    void stateChangingWrongMethodIsBlockedByCsrfBeforeReachingTheDispatcher() throws Exception {
        mockMvc.perform(put("/api/v1/auth/logout"))
                .andExpect(status().isForbidden());
    }
}
