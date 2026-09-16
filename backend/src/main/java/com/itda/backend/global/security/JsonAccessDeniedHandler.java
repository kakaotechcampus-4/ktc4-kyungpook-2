package com.itda.backend.global.security;

import com.itda.backend.global.exception.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonErrorResponseWriter jsonErrorResponseWriter;

    public JsonAccessDeniedHandler(JsonErrorResponseWriter jsonErrorResponseWriter) {
        this.jsonErrorResponseWriter = jsonErrorResponseWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        jsonErrorResponseWriter.write(response, CommonErrorCode.FORBIDDEN);
    }
}
