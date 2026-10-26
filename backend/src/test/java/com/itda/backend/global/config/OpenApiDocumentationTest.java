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
import static org.hamcrest.Matchers.hasItems;

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
				.andExpect(jsonPath("$.paths['/api/v1/institutions/me/children'].post.responses.403").exists())
				.andExpect(jsonPath("$.paths['/api/v1/institutions/me/children'].post.responses.409").exists())
				.andExpect(jsonPath("$.paths['/api/v1/institutions/me/children'].post.responses.503").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/run'].post.security[0].cookieAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/run'].post.requestBody").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/run'].post.responses.200").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/run'].post.responses.400").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/run'].post.responses.401").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/run'].post.responses.404").exists())
				.andExpect(jsonPath("$.components.schemas.RunSummaryRequest.properties.entryDateValid").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/gate1'].post.security[0].cookieAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/gate1'].post.requestBody").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/gate1'].post.responses.200").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/gate1'].post.responses.400").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/gate1'].post.responses.401").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/gate1'].post.responses.404.description",
						containsString("SUMMARY_NOT_FOUND")))
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/gate1'].post.responses.409.description",
						containsString("SUMMARY_ALREADY_REVIEWED")))
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/reopen'].post.security[0].cookieAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/reopen'].post.responses.200").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/reopen'].post.responses.404").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/reopen'].post.responses.409.description",
						containsString("SUMMARY_NOT_HELD")))
				.andExpect(jsonPath("$.paths['/api/v1/summaries'].get.security[0].cookieAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries'].get.parameters[0].name").value("gate1Status"))
				.andExpect(jsonPath("$.paths['/api/v1/summaries'].get.responses.200").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries'].get.responses.400").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries'].get.responses.403").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/gate1'].post.responses.403").exists())
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/reopen'].post.responses.403").exists())
				// 요청 스키마는 decision · cards · version 셋, 카드는 index · action · text 셋뿐이다. 검증용 메서드가 필드로 새면 안 된다.
				.andExpect(jsonPath("$.components.schemas.Gate1DecisionRequest.properties.length()").value(3))
				.andExpect(jsonPath("$.components.schemas.Gate1DecisionRequest.properties.cards").exists())
				.andExpect(jsonPath("$.components.schemas.Card.properties.length()").value(3))
				.andExpect(jsonPath("$.components.schemas.Card.properties.action.enum", hasItems("KEEP", "EDIT", "DELETE", "ADD")))
				.andExpect(jsonPath("$.components.schemas.Card.properties.shapeValid").doesNotExist())
				.andExpect(jsonPath("$.components.schemas.Gate1DecisionRequest.required", hasItems("decision", "version")))
				.andExpect(jsonPath("$.paths['/api/v1/summaries/{summaryId}/gate1'].post.responses.409.description",
						containsString("SUMMARY_CHANGED")))
				.andExpect(jsonPath("$.components.schemas.Gate1DecisionRequest.properties.cardsValid").doesNotExist());
	}

	/** 아동 등록·명부 스키마에 실제 필드만 있어야 한다. 검증용 메서드나 범위 밖 필드가 새면 안 된다. */
	@Test
	void registerChildSchemasExposeOnlyContractFields() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.schemas.RegisterChildRequest.properties.length()").value(3))
				.andExpect(jsonPath("$.components.schemas.RegisterChildRequest.properties.externalId.maxLength").value(50))
				.andExpect(jsonPath("$.components.schemas.RegisterChildRequest.required", hasItems("name", "birthDate", "externalId")))
				.andExpect(jsonPath("$.components.schemas.RegisterChildRequest.properties.name").exists())
				.andExpect(jsonPath("$.components.schemas.RegisterChildRequest.properties.birthDate.format").value("date"))
				.andExpect(jsonPath("$.components.schemas.RegisterChildRequest.properties.birthDateValid").doesNotExist())
				.andExpect(jsonPath("$.components.schemas.RegisterChildRequest.properties.birthDateValue").doesNotExist())
				.andExpect(jsonPath("$.components.schemas.RegisteredChildResponse.properties.length()").value(2))
				.andExpect(jsonPath("$.components.schemas.RegisteredChildResponse.properties.child").exists())
				.andExpect(jsonPath("$.components.schemas.RegisteredChildResponse.properties.invitation").exists())
				.andExpect(jsonPath("$.components.schemas.IssuedInvitationResponse.properties.length()").value(3))
				.andExpect(jsonPath("$.components.schemas.IssuedInvitationResponse.properties.expiresAt.format").value("date-time"))
				.andExpect(jsonPath("$.components.schemas.RegisteredChild.properties.length()").value(5))
				.andExpect(jsonPath("$.components.schemas.RegisteredChild.properties.externalId").exists())
				.andExpect(jsonPath("$.components.schemas.RegisteredChild.properties.id").exists())
				.andExpect(jsonPath("$.components.schemas.RegisteredChild.properties.name").exists())
				.andExpect(jsonPath("$.components.schemas.RegisteredChild.properties.birthDate").exists())
				.andExpect(jsonPath("$.components.schemas.RegisteredChild.properties.status").exists())
				.andExpect(jsonPath("$.components.schemas.ChildRosterResponse.properties.length()").value(5))
				.andExpect(jsonPath("$.components.schemas.ChildRosterResponse.properties.externalId").exists());
	}

	/** 초대 코드·보호자 연결 API(O-15~O-19, G-04·G-05). 모두 쿠키 인증을 요구하고, 권한·소속 오류가 명세에 드러나야 한다. */
	@Test
	void invitationAndGuardianLinkApisAreDocumented() throws Exception {
		String invitations = "$.paths['/api/v1/institutions/me/children/{childId}/invitations']";
		String invitation = "$.paths['/api/v1/institutions/me/children/{childId}/invitations/{invitationId}']";
		String guardians = "$.paths['/api/v1/institutions/me/children/{childId}/guardians']";
		String guardian = "$.paths['/api/v1/institutions/me/children/{childId}/guardians/{guardianUserId}']";
		String accept = "$.paths['/api/v1/guardians/me/invitations/accept']";
		String myChild = "$.paths['/api/v1/guardians/me/children/{childId}']";

		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(invitations + ".post.security[0].cookieAuth").exists())
				.andExpect(jsonPath(invitations + ".post.responses.201").exists())
				.andExpect(jsonPath(invitations + ".post.responses.401").exists())
				.andExpect(jsonPath(invitations + ".post.responses.403").exists())
				.andExpect(jsonPath(invitations + ".post.responses.404.description").value(containsString("CHILD_NOT_FOUND")))
				.andExpect(jsonPath(invitations + ".post.responses.503").exists())
				.andExpect(jsonPath(invitations + ".get.security[0].cookieAuth").exists())
				.andExpect(jsonPath(invitations + ".get.responses.200").exists())
				.andExpect(jsonPath(invitations + ".get.responses.404").exists())
				.andExpect(jsonPath(invitation + ".delete.security[0].cookieAuth").exists())
				.andExpect(jsonPath(invitation + ".delete.responses.204").exists())
				.andExpect(jsonPath(invitation + ".delete.responses.404.description").value(containsString("INVITATION_NOT_FOUND")))
				.andExpect(jsonPath(guardians + ".get.security[0].cookieAuth").exists())
				.andExpect(jsonPath(guardians + ".get.responses.200").exists())
				.andExpect(jsonPath(guardians + ".get.responses.404").exists())
				.andExpect(jsonPath(guardian + ".delete.security[0].cookieAuth").exists())
				.andExpect(jsonPath(guardian + ".delete.responses.204").exists())
				.andExpect(jsonPath(guardian + ".delete.responses.403.description")
						.value(containsString("GUARDIAN_LINKED_BY_OTHER_ORGANIZATION")))
				.andExpect(jsonPath(guardian + ".delete.responses.404.description").value(containsString("GUARDIAN_NOT_FOUND")))
				.andExpect(jsonPath(accept + ".post.security[0].cookieAuth").exists())
				.andExpect(jsonPath(accept + ".post.requestBody").exists())
				.andExpect(jsonPath(accept + ".post.responses.200").exists())
				.andExpect(jsonPath(accept + ".post.responses.400.description").value(containsString("INVALID_INVITE_CODE")))
				.andExpect(jsonPath(accept + ".post.responses.401").exists())
				.andExpect(jsonPath(accept + ".post.responses.403.description").value(containsString("PARENT_REQUIRED")))
				.andExpect(jsonPath(accept + ".post.responses.409.description").value(containsString("LINK_ALREADY_EXISTS")))
				.andExpect(jsonPath(myChild + ".delete.security[0].cookieAuth").exists())
				.andExpect(jsonPath(myChild + ".delete.responses.204").exists())
				.andExpect(jsonPath(myChild + ".delete.responses.403").exists())
				.andExpect(jsonPath("$.components.schemas.AcceptInvitationRequest.properties.length()").value(1))
				.andExpect(jsonPath("$.components.schemas.AcceptedInvitationResponse.properties.childStatus").exists())
				.andExpect(jsonPath("$.components.schemas.AcceptedInvitationResponse.properties.organizationId").exists())
				.andExpect(jsonPath("$.components.schemas.ChildGuardianResponse.properties.linkedByThisOrganization").exists());
	}

	/** 동의 미리보기·저장 API(G-02·G-41). 요청 스키마에 조건부 검증 메서드가 필드처럼 새면 안 된다. */
	@Test
	void consentApisAreDocumented() throws Exception {
		String preview = "$.paths['/api/v1/children/{childId}/consent-preview']";
		String scopes = "$.paths['/api/v1/children/{childId}/consent-scopes/{institutionId}']";

		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(preview + ".get.security[0].cookieAuth").exists())
				.andExpect(jsonPath(preview + ".get.responses.200").exists())
				.andExpect(jsonPath(preview + ".get.responses.401").exists())
				.andExpect(jsonPath(preview + ".get.responses.403.description").value(containsString("PARENT_REQUIRED")))
				.andExpect(jsonPath(preview + ".get.responses.404.description").value(containsString("CONSENT_TARGET_NOT_FOUND")))
				.andExpect(jsonPath(scopes + ".put.security[0].cookieAuth").exists())
				.andExpect(jsonPath(scopes + ".put.requestBody").exists())
				.andExpect(jsonPath(scopes + ".put.responses.200").exists())
				.andExpect(jsonPath(scopes + ".put.responses.400.description").value(containsString("INVALID_REQUEST")))
				.andExpect(jsonPath(scopes + ".put.responses.401").exists())
				.andExpect(jsonPath(scopes + ".put.responses.403.description").value(containsString("FORBIDDEN")))
				.andExpect(jsonPath(scopes + ".put.responses.404.description").value(containsString("CONSENT_TARGET_NOT_FOUND")))
				.andExpect(jsonPath("$.components.schemas.UpdateConsentRequest.properties.length()").value(2))
				.andExpect(jsonPath("$.components.schemas.UpdateConsentRequest.properties.action").exists())
				.andExpect(jsonPath("$.components.schemas.UpdateConsentRequest.properties.allowedFields").exists())
				.andExpect(jsonPath("$.components.schemas.UpdateConsentRequest.properties.allowedFieldsValidForAction").doesNotExist())
				.andExpect(jsonPath("$.components.schemas.ConsentPreviewResponse.properties.allowedFields").exists())
				.andExpect(jsonPath("$.components.schemas.ConsentPreviewResponse.properties.documentUrl").exists())
				.andExpect(jsonPath("$.components.schemas.ConsentResponse.properties.length()").value(3))
				.andExpect(jsonPath("$.components.schemas.InstitutionResponse.properties.verified").exists());
	}

	/** 보호자 아이 목록·홈(G-10·G-11). school·care 는 저장하는 곳이 없어 스키마에도 없어야 한다. */
	@Test
	void guardianChildApisAreDocumented() throws Exception {
		String children = "$.paths['/api/v1/guardians/me/children']";
		String home = "$.paths['/api/v1/guardians/me/home']";

		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(children + ".get.security[0].cookieAuth").exists())
				.andExpect(jsonPath(children + ".get.responses.200").exists())
				.andExpect(jsonPath(children + ".get.responses.401").exists())
				.andExpect(jsonPath(children + ".get.responses.403.description").value(containsString("PARENT_REQUIRED")))
				.andExpect(jsonPath(home + ".get.security[0].cookieAuth").exists())
				.andExpect(jsonPath(home + ".get.parameters[?(@.name == 'childId')].required", hasItems(true)))
				.andExpect(jsonPath(home + ".get.responses.200").exists())
				.andExpect(jsonPath(home + ".get.responses.400.description").value(containsString("INVALID_REQUEST")))
				.andExpect(jsonPath(home + ".get.responses.401").exists())
				.andExpect(jsonPath(home + ".get.responses.403.description").value(containsString("FORBIDDEN")))
				.andExpect(jsonPath("$.components.schemas.GuardianChildResponse.properties.institutions").exists())
				.andExpect(jsonPath("$.components.schemas.GuardianChildResponse.properties.school").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.components.schemas.GuardianChildResponse.properties.care").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.components.schemas.ChildInstitution.properties.consent").exists())
				.andExpect(jsonPath("$.components.schemas.GuardianHomeResponse.properties.activity.items['$ref']")
						.value(containsString("ParentActivityResponse")))
				.andExpect(jsonPath("$.components.schemas.ParentActivityResponse.properties.length()").value(4))
				.andExpect(jsonPath("$.components.schemas.ParentActivityResponse.properties.id").exists())
				.andExpect(jsonPath("$.components.schemas.ParentActivityResponse.properties.at").exists())
				.andExpect(jsonPath("$.components.schemas.ParentActivityResponse.properties.text").exists())
				.andExpect(jsonPath("$.components.schemas.ParentActivityResponse.properties.journalId").exists());
	}

	@Test
	void swaggerUiIsAvailable() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk());
	}
}
