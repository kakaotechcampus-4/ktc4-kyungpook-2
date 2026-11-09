package com.itda.backend.global.config;

import java.util.Arrays;
import java.util.List;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;

import com.itda.backend.global.exception.ErrorResponse;

@Configuration
public class OpenApiConfig {

	public static final String COOKIE_AUTH_SCHEME = "cookieAuth";

	private static final String ERROR_RESPONSE_SCHEMA = "ErrorResponse";

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
								.description("로그인할 때 발급하는 httpOnly 원본 JWT 쿠키입니다. 현재 로그인을 선택하며, "
										+ "갱신 JWT 는 access_token_renewal_{sid} 쿠키로 전달합니다. "
										+ "브라우저가 두 쿠키를 자동 전송하고, 서버는 현재 로그인의 유효한 JWT 만 인증합니다.")))
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

	/**
	 * 컨트롤러마다 반복하던 문서 표기를 한 곳에서 붙인다.
	 * <ul>
	 *   <li>{@link SecurityConfig#PUBLIC_PATHS} 밖의 경로는 쿠키 인증 자물쇠와 401 응답을 단다. 엔드포인트가 401 을 따로
	 *       설명했으면 그대로 둔다.</li>
	 *   <li>4xx · 5xx 응답은 모두 {@link ErrorResponse} 본문이다.</li>
	 * </ul>
	 */
	@Bean
	public OpenApiCustomizer commonResponsesCustomizer() {
		AntPathMatcher matcher = new AntPathMatcher();
		return openApi -> {
			openApi.getComponents().addSchemas(ERROR_RESPONSE_SCHEMA,
					ModelConverters.getInstance(true).readAllAsResolvedSchema(ErrorResponse.class).schema);
			openApi.getPaths().forEach((path, item) -> {
				boolean isPublic = Arrays.stream(SecurityConfig.PUBLIC_PATHS)
						.anyMatch(pattern -> matcher.match(pattern, path));
				item.readOperations().forEach(operation -> {
					if (!isPublic) {
						operation.setSecurity(List.of(new SecurityRequirement().addList(COOKIE_AUTH_SCHEME)));
						operation.getResponses().putIfAbsent("401", new ApiResponse().description("인증 필요"));
					}
					operation.getResponses().forEach((code, response) -> {
						if ((code.startsWith("4") || code.startsWith("5")) && response.get$ref() == null) {
							response.setContent(new Content().addMediaType("*/*", new MediaType()
									.schema(new Schema<>().$ref("#/components/schemas/" + ERROR_RESPONSE_SCHEMA))));
						}
					});
				});
			});
		};
	}
}
