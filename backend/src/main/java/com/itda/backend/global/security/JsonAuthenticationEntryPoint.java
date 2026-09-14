package com.itda.backend.global.security;

import com.itda.backend.global.exception.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonErrorResponseWriter jsonErrorResponseWriter;

    public JsonAuthenticationEntryPoint(JsonErrorResponseWriter jsonErrorResponseWriter) {
        this.jsonErrorResponseWriter = jsonErrorResponseWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        jsonErrorResponseWriter.write(response, CommonErrorCode.UNAUTHORIZED);
    }
}
