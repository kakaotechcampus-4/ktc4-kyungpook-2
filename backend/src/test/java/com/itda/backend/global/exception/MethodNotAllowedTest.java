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

    @Test
    void methodNotAllowedResponseCarriesAllowHeader() throws Exception {
        mockMvc.perform(get("/api/v1/auth/logout"))
                .andExpect(header().string("Allow", Matchers.containsString("POST")));
    }

    @Test
    void stateChangingWrongMethodIsBlockedByCsrfBeforeReachingTheDispatcher() throws Exception {
        mockMvc.perform(put("/api/v1/auth/logout"))
                .andExpect(status().isForbidden());
    }
}
