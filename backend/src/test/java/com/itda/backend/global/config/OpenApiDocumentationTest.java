package com.itda.backend.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocumentationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void openApiDocumentIncludesCookieAuthenticationAndOauthLoginFlow() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openapi").value("3.1.0"))
				.andExpect(jsonPath("$.info.title").value("ITDA API"))
				.andExpect(jsonPath("$.info.version").value("v1"))
				.andExpect(jsonPath("$.components.securitySchemes.cookieAuth.type").value("apiKey"))
				.andExpect(jsonPath("$.components.securitySchemes.cookieAuth.in").value("cookie"))
				.andExpect(jsonPath("$.components.securitySchemes.cookieAuth.name").value("access_token"))
				.andExpect(jsonPath("$.paths['/oauth2/authorization/kakao'].get.responses.302").exists())
				.andExpect(jsonPath("$.paths['/login/oauth2/code/kakao'].get.responses.302").exists())
				.andExpect(jsonPath("$.paths['/api/v1/raw-records'].post.responses.201").exists())
				.andExpect(jsonPath("$.paths['/api/v1/raw-records'].post.security[0].cookieAuth").exists());
	}

	@Test
	void swaggerUiIsAvailable() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk());
	}
}
