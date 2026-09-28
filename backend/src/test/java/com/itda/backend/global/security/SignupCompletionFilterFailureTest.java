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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SignupCompletionFilterFailureTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void databaseFailureIsNotTurnedIntoAnAuthenticationDecision() {
        UserService service = mock(UserService.class);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database offline");
        when(service.isSignupCompleted("1")).thenThrow(failure);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null, List.of()));
        var filter = new SignupCompletionFilter(service, new JsonErrorResponseWriter(new ObjectMapper()));
        var request = new MockHttpServletRequest("GET", "/api/v1/security-test/probe");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(failure);
        assertThat(response.getContentAsByteArray()).isEmpty();
        verifyNoInteractions(chain);
    }
}
