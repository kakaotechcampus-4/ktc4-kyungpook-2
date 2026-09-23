package com.itda.backend.global.exception;

import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC 표준 예외가 catch-all 에 먹혀 500 이 되지 않고, 알맞은 4xx 와 규약 본문으로
 * 나가는지 고정한다. 인증을 통과한 뒤의 동작을 보려고 실제 JWT 쿠키를 싣는다.
 *
 * <p>{@code csrf()} 헬퍼를 쓰지 않는다 — 공유 컨텍스트의 CsrfFilter 저장소를 테스트용으로
 * 바꿔 끼워서, 뒤에 실행되는 다른 테스트 클래스에서 XSRF-TOKEN 쿠키가 발급되지 않게 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    private static final String RAW_RECORDS = "/api/v1/raw-records";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    private Cookie authCookie;
    private Cookie csrfCookie;

    @BeforeEach
    void setUp() throws Exception {
        authCookie = new Cookie(JwtCookie.NAME, jwtProvider.createToken("kakao-1"));
        csrfCookie = mockMvc.perform(get("/api/health"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    /** 쓰기 요청이 CSRF 검사에서 막히지 않고 컨트롤러까지 가도록 실제 토큰을 싣는다. */
    private MockHttpServletRequestBuilder withAuthAndCsrf(MockHttpServletRequestBuilder builder) {
        return builder.cookie(authCookie, csrfCookie).header("X-XSRF-TOKEN", csrfCookie.getValue());
    }

    @Test
    void pathVariableTypeMismatchReturns400() throws Exception {
        mockMvc.perform(get(RAW_RECORDS + "/abc").cookie(authCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unknownPathReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/unknown-path").cookie(authCookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unsupportedMethodReturns405WithAllowHeader() throws Exception {
        mockMvc.perform(withAuthAndCsrf(delete(RAW_RECORDS)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    /** {@code @RequestParam MultipartFile} 누락은 파라미터가 아니라 파트 누락 예외로 온다. */
    @Test
    void missingMultipartFileReturns400() throws Exception {
        mockMvc.perform(withAuthAndCsrf(multipart(RAW_RECORDS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unsupportedContentTypeReturns415() throws Exception {
        mockMvc.perform(withAuthAndCsrf(post(RAW_RECORDS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    /** 인증 필터가 먼저 막으므로, 없는 경로라도 비로그인이면 404 가 아니라 401 이다. */
    @Test
    void unknownPathWithoutAuthenticationStillReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/unknown-path"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
