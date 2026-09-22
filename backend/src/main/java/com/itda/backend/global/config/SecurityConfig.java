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
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
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
                 * STATELESS 에서 IF_REQUIRED 로 바꾼다.
                 *
                 * oauth2Login 은 인가 요청(state 포함)을 카카오에 다녀오는 동안 어딘가
                 * 보관해야 하고, 기본 보관소가 HttpSession 이다. STATELESS 면 세션이 아예
                 * 만들어지지 않아 로그인이 성립하지 않는다.
                 *
                 * 세션은 인가 화면에 다녀오는 몇 초 동안만 살아 있고, 성공·실패 핸들러가
                 * 곧바로 invalidate 한다. 그 뒤의 모든 API 요청은 여전히 JWT 쿠키만 보는
                 * 무상태 방식이다.
                 *
                 * 대가: 서버를 재시작하면 그 순간 로그인 중이던 사용자는 다시 눌러야 하고,
                 * 인스턴스를 늘리면 sticky session 이나 공유 세션 저장소가 필요해진다.
                 * 지금은 EC2 한 대라 문제되지 않는다.
                 */
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/api/v1/auth/**",
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
