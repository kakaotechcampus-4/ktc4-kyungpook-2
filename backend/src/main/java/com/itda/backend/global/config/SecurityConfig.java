package com.itda.backend.global.config;

import com.itda.backend.global.security.oauth.LoginRoleHintFilter;
import com.itda.backend.global.security.oauth.OAuth2LoginFailureHandler;
import com.itda.backend.global.security.oauth.OAuth2LoginSuccessHandler;
import com.itda.backend.global.jwt.JwtAuthenticationFilter;
import com.itda.backend.global.security.CsrfCookieFilter;
import com.itda.backend.global.security.JsonAccessDeniedHandler;
import com.itda.backend.global.security.JsonAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint;
    private final JsonAccessDeniedHandler jsonAccessDeniedHandler;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;
    private final LoginRoleHintFilter loginRoleHintFilter;

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String[] allowedOrigins;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint,
            JsonAccessDeniedHandler jsonAccessDeniedHandler,
            OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
            OAuth2LoginFailureHandler oAuth2LoginFailureHandler,
            LoginRoleHintFilter loginRoleHintFilter
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jsonAuthenticationEntryPoint = jsonAuthenticationEntryPoint;
        this.jsonAccessDeniedHandler = jsonAccessDeniedHandler;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
        this.oAuth2LoginFailureHandler = oAuth2LoginFailureHandler;
        this.loginRoleHintFilter = loginRoleHintFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                /*
                 * 출입증이 쿠키로 바뀌면서 CSRF 방어가 필요해졌다. 브라우저가 쿠키를 자동으로
                 * 붙여 보내므로, 남의 사이트에서 띄운 폼 하나로 우리 API 가 호출될 수 있다.
                 * (헤더 방식일 때는 프론트가 직접 붙여야 해서 이 문제가 없었다.)
                 *
                 * 토큰을 쿠키로 내려주고 프론트가 X-XSRF-TOKEN 헤더로 되돌려보내는 방식이다.
                 * 이 쿠키만은 httpOnly 가 아니어야 한다 — 프론트 JS 가 읽어야 하기 때문이다.
                 * 출입증 쿠키와 달리 이 값은 새어도 그 자체로는 아무 권한이 없다.
                 */
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // 로그인 진입·콜백은 카카오에서 넘어오는 흐름이라 토큰을 실을 수 없다.
                        .ignoringRequestMatchers("/oauth2/**", "/login/oauth2/**")
                )
                // 토큰은 게으르게 만들어진다. 아무도 건드리지 않으면 쿠키가 나가지 않아
                // 프론트가 토큰을 구할 수 없고 모든 쓰기 요청이 403 이 된다. 아래 필터가 그걸 막는다.
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                /*
                 * 세션 정책은 기본값(IF_REQUIRED)을 쓴다. STATELESS 로 두면 안 된다.
                 *
                 * oauth2Login 은 인가 요청(state 포함)을 카카오에 다녀오는 동안 어딘가
                 * 보관해야 하고, 기본 보관소가 HttpSession 이다. STATELESS 면 세션이 아예
                 * 만들어지지 않아 로그인이 성립하지 않는다.
                 *
                 * 세션은 인가 화면에 다녀오는 몇 초 동안만 살아 있고, 성공·실패 핸들러가
                 * 곧바로 invalidate 한다. 그 뒤의 모든 API 요청은 JWT 쿠키만 보는 무상태 방식이다.
                 *
                 * <b>sessionCreationPolicy(IF_REQUIRED) 를 명시적으로 쓰지 않는다.</b> 기본값과 같은 값이라도
                 * 명시하면 Spring 이 SessionManagementFilter 를 끼워 넣는다. 이 필터는 출입증으로 인증된
                 * 요청을 매번 "방금 새로 로그인한 사람" 으로 오해하고 로그인 직후 절차를 돌려서 두 가지 버그를 냈다.
                 *  - 인증을 세션에 저장해, 로그아웃으로 출입증을 지워도 JSESSIONID 로 로그인이 유지됐다.
                 *  - CsrfAuthenticationStrategy 가 XSRF-TOKEN 쿠키를 비워, 로그인한 뒤 GET 한 번이면
                 *    다음 쓰기 요청(업로드·로그아웃)이 403 이 됐다.
                 * oauth2Login 쪽 세션 처리(세션 ID 교체 등)는 로그인 필터가 직접 하므로 이 필터가 없어도 된다.
                 *
                 * 대가: 서버를 재시작하면 그 순간 로그인 중이던 사용자는 다시 눌러야 하고,
                 * 인스턴스를 늘리면 sticky session 이나 공유 세션 저장소가 필요해진다.
                 * 지금은 EC2 한 대라 문제되지 않는다.
                 */
                /*
                 * 로그인 정보(SecurityContext)를 세션에 저장하지 않는다. 출입증 쿠키만이 인증 수단이다.
                 * 요청 속성 저장소는 그 요청 안에서만 인증을 들고 있다가 버린다. 세션은 위에 적은 대로
                 * 카카오 인가 요청(state)과 역할 힌트를 잠깐 담는 용도로만 남는다.
                 */
                .securityContext(context -> context
                        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
                /*
                 * 규칙은 먼저 맞는 것이 이긴다. "/api/v1/auth/**" 를 통째로 열어두면
                 * 그 아래 새로 만드는 API 가 전부 인증 없이 뚫리므로, 열 것만 하나씩 적는다.
                 * /api/v1/auth/me 는 anyRequest().authenticated() 가 잡아,
                 * 비로그인 요청은 JsonAuthenticationEntryPoint 가 401 JSON 으로 응답한다.
                 */
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/oauth2/**",
                                "/login/oauth2/**",
                                // 메서드로 좁히지 않는다. POST 만 열면 익명 GET 요청이
                                // DispatcherServlet 에 닿기 전에 401 로 끝나서, 405 로 응답해야 할
                                // 잘못된 메서드 호출이 인증 오류로 둔갑한다.
                                "/api/v1/auth/logout",
                                "/api/health",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(oAuth2LoginSuccessHandler)
                        .failureHandler(oAuth2LoginFailureHandler)
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jsonAuthenticationEntryPoint)
                        .accessDeniedHandler(jsonAccessDeniedHandler)
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                /*
                 * 역할 힌트는 인가 요청이 만들어지기 전에 세션에 들어가야 한다.
                 * OAuth2AuthorizationRequestRedirectFilter 가 /oauth2/authorization/** 를 받아
                 * 인가 요청을 세션에 저장하고 곧바로 302 를 내보내므로, 그 뒤에 두면 아예 실행되지 않는다.
                 */
                .addFilterBefore(loginRoleHintFilter, OAuth2AuthorizationRequestRedirectFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
