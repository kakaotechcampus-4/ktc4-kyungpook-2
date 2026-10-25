package com.itda.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 설정을 켜지 않으면(기본값) 개발용 로그인은 경로도, API 문서 항목도 없다. */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:dev-login-disabled;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DevLoginDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void devLoginDoesNotExistByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/dev/login").param("as", "parent1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void openApiDoesNotDocumentDevLoginByDefault() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/dev/login']").doesNotExist());
    }
}
