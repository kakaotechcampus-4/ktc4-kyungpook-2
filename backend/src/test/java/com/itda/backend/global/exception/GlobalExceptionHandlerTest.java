package com.itda.backend.global.exception;

import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    private static final String RAW_RECORDS = "/api/v1/raw-records";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TermsAgreementRepository termsAgreementRepository;

    private Cookie authCookie;
    private Cookie csrfCookie;

    @BeforeEach
    void setUp() throws Exception {
        User member = userRepository.findByKakaoId("exception-handler-test")
                .orElseGet(() -> TermsFixture.agreeAll(termsAgreementRepository,
                        userRepository.saveAndFlush(UserFixture.parent("exception-handler-test"))));
        authCookie = new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(member.getId())));
        csrfCookie = mockMvc.perform(get("/api/health"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

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

    @Test
    void unknownPathWithoutAuthenticationStillReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/unknown-path"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
