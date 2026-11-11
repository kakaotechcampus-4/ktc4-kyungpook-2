package com.itda.backend.global.config;

import com.itda.backend.global.jwt.JwtAuthenticationFilter;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.global.security.ApiPaths;
import com.itda.backend.global.security.CsrfCookieFilter;
import com.itda.backend.global.security.JsonAccessDeniedHandler;
import com.itda.backend.global.security.JsonAuthenticationEntryPoint;
import com.itda.backend.global.security.JsonErrorResponseWriter;
import com.itda.backend.global.security.SignupCompletionFilter;
import com.itda.backend.global.security.oauth.OAuth2LoginFailureHandler;
import com.itda.backend.global.security.oauth.OAuth2LoginSuccessHandler;
import com.itda.backend.service.user.RevokedSessionService;
import com.itda.backend.service.user.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
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

    public static final String[] PUBLIC_PATHS = {
            "/oauth2/**",
            "/login/oauth2/**",
            "/api/v1/auth/logout",
            "/api/v1/dev/login",
            "/api/health",
            ApiPaths.KAKAO_UNLINK_WEBHOOK,
            "/api/v1/terms",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private final JwtProvider jwtProvider;
    private final JwtCookie jwtCookie;
    private final RevokedSessionService revokedSessionService;
    private final JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint;
    private final JsonAccessDeniedHandler jsonAccessDeniedHandler;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;
    private final UserService userService;
    private final JsonErrorResponseWriter jsonErrorResponseWriter;

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String[] allowedOrigins;

    public SecurityConfig(
            JwtProvider jwtProvider,
            JwtCookie jwtCookie,
            RevokedSessionService revokedSessionService,
            JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint,
            JsonAccessDeniedHandler jsonAccessDeniedHandler,
            OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
            OAuth2LoginFailureHandler oAuth2LoginFailureHandler,
            UserService userService,
            JsonErrorResponseWriter jsonErrorResponseWriter
    ) {
        this.jwtProvider = jwtProvider;
        this.jwtCookie = jwtCookie;
        this.revokedSessionService = revokedSessionService;
        this.jsonAuthenticationEntryPoint = jsonAuthenticationEntryPoint;
        this.jsonAccessDeniedHandler = jsonAccessDeniedHandler;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
        this.oAuth2LoginFailureHandler = oAuth2LoginFailureHandler;
        this.userService = userService;
        this.jsonErrorResponseWriter = jsonErrorResponseWriter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/oauth2/**", "/login/oauth2/**", ApiPaths.KAKAO_UNLINK_WEBHOOK)
                )
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .securityContext(context -> context
                        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
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
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtProvider, jwtCookie, revokedSessionService, jsonErrorResponseWriter),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(
                        new SignupCompletionFilter(userService, jsonErrorResponseWriter),
                        JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of(ApiPaths.USER_ID_HEADER));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
