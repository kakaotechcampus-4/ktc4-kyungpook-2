package com.itda.backend.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	public static final String COOKIE_AUTH_SCHEME = "cookieAuth";

	@Bean
	public OpenAPI itdaOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("ITDA API")
						.version("v1")
						.description("잇다 서비스 API 문서입니다. 보호 API는 카카오 로그인 후 "
								+ "발급되는 httpOnly access_token 쿠키로 인증합니다."))
				.components(new Components()
						.addSecuritySchemes(COOKIE_AUTH_SCHEME, new SecurityScheme()
								.type(SecurityScheme.Type.APIKEY)
								.in(SecurityScheme.In.COOKIE)
								.name("access_token")
								.description("카카오 로그인 성공 후 서버가 발급하는 httpOnly JWT 쿠키입니다.")))
				.paths(new Paths()
						.addPathItem("/oauth2/authorization/kakao", oauthAuthorizationPath())
						.addPathItem("/login/oauth2/code/kakao", oauthCallbackPath()));
	}

	private PathItem oauthAuthorizationPath() {
		return new PathItem().get(new Operation()
				.addTagsItem("인증")
				.summary("카카오 로그인 시작")
				.description("브라우저를 카카오 로그인·동의 화면으로 리다이렉트합니다. "
						+ "fetch가 아닌 브라우저 페이지 이동으로 호출합니다.")
				.responses(new ApiResponses().addApiResponse("302",
						new ApiResponse().description("카카오 로그인 페이지로 리다이렉트"))));
	}

	private PathItem oauthCallbackPath() {
		return new PathItem().get(new Operation()
				.addTagsItem("인증")
				.summary("카카오 로그인 콜백")
				.description("카카오가 인가 코드 또는 오류를 전달하는 서버 전용 콜백입니다. "
						+ "클라이언트가 직접 호출하지 않습니다. 성공 시 access_token 쿠키를 발급한 뒤 "
						+ "설정된 프론트엔드 주소로 리다이렉트합니다.")
				.responses(new ApiResponses().addApiResponse("302",
						new ApiResponse().description("로그인 성공 또는 실패 페이지로 리다이렉트"))));
	}
}
