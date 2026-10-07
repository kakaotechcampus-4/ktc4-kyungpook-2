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
import static org.hamcrest.Matchers.containsString;

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
				.andExpect(jsonPath("$.paths['/api/v1/raw-records'].post.security[0].cookieAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/raw-records'].post.responses.400").exists())
				.andExpect(jsonPath("$.paths['/api/v1/raw-records'].post.responses.413").exists())
				.andExpect(jsonPath("$.paths['/api/v1/raw-records'].post.responses.500").exists())
				.andExpect(jsonPath("$.paths['/api/v1/raw-records'].get.responses.401.description")
						.value(containsString("SESSION_USER_NOT_FOUND")))
				.andExpect(jsonPath("$.paths['/api/v1/raw-records'].get.responses.403.description")
						.value(containsString("SIGNUP_NOT_COMPLETED")))
				.andExpect(jsonPath("$.paths['/api/v1/raw-records'].get.responses.500.description")
						.value(containsString("INTERNAL_SERVER_ERROR")))
				.andExpect(jsonPath("$.paths['/api/v1/raw-records/{id}'].get.responses.500.description")
						.value(containsString("INTERNAL_SERVER_ERROR")))
				.andExpect(jsonPath("$.paths['/api/v1/raw-records/{id}'].get.responses.403").exists())
				.andExpect(jsonPath("$.paths['/api/v1/raw-records/{id}'].get.responses.404").exists())
				// 세션 조회는 보호 API 다 — 쿠키 인증 요구와 401 이 명세에 드러나야 한다.
				.andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.security[0].cookieAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.responses.200").exists())
				.andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.responses.401").exists())
				// 회원가입은 보호 API 이고, 가입 완료 응답을 201 로 준다.
				.andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.security[0].cookieAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.responses.201").exists())
				.andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.responses.400").exists())
				.andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.responses.401").exists())
				.andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.responses.403").exists())
				.andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.responses.409").exists())
				// 요청 스키마에는 실제 요청 필드만 있어야 한다. 검증용 is*() 메서드가 필드로 새면 안 된다.
				.andExpect(jsonPath("$.components.schemas.SignupRequest.properties.role").exists())
				.andExpect(jsonPath("$.components.schemas.SignupRequest.properties.businessNumber").exists())
				.andExpect(jsonPath("$.components.schemas.SignupRequest.properties.organizationSignup").doesNotExist())
				.andExpect(jsonPath("$.components.schemas.SignupRequest.properties.organizationInfoMatchingRole").doesNotExist())
				// 로그아웃은 본문 없는 204 다. CSRF 토큰이 없으면 403.
				.andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.responses.204").exists())
				.andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.responses.403").exists())
				.andExpect(jsonPath("$.paths['/api/v1/matching-queue'].get.responses.200").exists())
				.andExpect(jsonPath("$.paths['/api/v1/matching-queue'].get.security[0].cookieAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/matching-queue/{id}/resolve'].post.responses.400").exists())
				.andExpect(jsonPath("$.paths['/api/v1/matching-queue/{id}/resolve'].post.responses.404").exists())
				.andExpect(jsonPath("$.paths['/api/v1/institutions/me/children'].get.responses.200").exists())
				.andExpect(jsonPath("$.paths['/api/v1/institutions/me/children'].get.security[0].cookieAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/institutions/me/children'].post.security[0].cookieAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/institutions/me/children'].post.requestBody").exists())
				.andExpect(jsonPath("$.paths['/api/v1/institutions/me/children'].post.responses.201").exists())
				.andExpect(jsonPath("$.paths['/api/v1/institutions/me/children'].post.responses.400").exists())
				.andExpect(jsonPath("$.paths['/api/v1/institutions/me/children'].post.responses.401").exists())
				.andExpect(jsonPath("$.paths['/api/v1/institutions/me/children'].post.responses.403").exists());
	}

	/** 아동 등록 요청·응답 스키마에 실제 필드만 있어야 한다. 검증용 메서드나 범위 밖 필드가 새면 안 된다. */
	@Test
	void registerChildSchemasExposeOnlyContractFields() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.schemas.RegisterChildRequest.properties.length()").value(2))
				.andExpect(jsonPath("$.components.schemas.RegisterChildRequest.properties.name").exists())
				.andExpect(jsonPath("$.components.schemas.RegisterChildRequest.properties.birthDate.format").value("date"))
				.andExpect(jsonPath("$.components.schemas.RegisterChildRequest.properties.birthDateValid").doesNotExist())
				.andExpect(jsonPath("$.components.schemas.RegisterChildRequest.properties.birthDateValue").doesNotExist())
				.andExpect(jsonPath("$.components.schemas.RegisteredChildResponse.properties.length()").value(1))
				.andExpect(jsonPath("$.components.schemas.RegisteredChildResponse.properties.child").exists())
				.andExpect(jsonPath("$.components.schemas.RegisteredChild.properties.length()").value(4))
				.andExpect(jsonPath("$.components.schemas.RegisteredChild.properties.id").exists())
				.andExpect(jsonPath("$.components.schemas.RegisteredChild.properties.name").exists())
				.andExpect(jsonPath("$.components.schemas.RegisteredChild.properties.birthDate").exists())
				.andExpect(jsonPath("$.components.schemas.RegisteredChild.properties.status").exists());
	}

	@Test
	void swaggerUiIsAvailable() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk());
	}
}
