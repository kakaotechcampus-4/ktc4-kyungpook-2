package com.itda.backend.global.security;

import java.util.List;

import jakarta.servlet.FilterChain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SignupCompletionFilterFailureTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void databaseFailureReturns500WithoutClearingAuthentication() throws Exception {
        UserService service = mock(UserService.class);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database offline");
        when(service.isSignupCompleted("1")).thenThrow(failure);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null, List.of()));
        var filter = new SignupCompletionFilter(service, new JsonErrorResponseWriter(new ObjectMapper()));
        var request = new MockHttpServletRequest("GET", "/api/v1/security-test/probe");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(new ObjectMapper().readTree(response.getContentAsString()).get("code").asText())
                .isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getContentAsString()).doesNotContain("database offline");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo("1");
        verifyNoInteractions(chain);
    }
}
