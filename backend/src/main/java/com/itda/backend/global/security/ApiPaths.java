package com.itda.backend.global.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.util.UrlPathHelper;

public final class ApiPaths {

    public static final String API_PREFIX = "/api/";

    public static final String AUTH_ME = "/api/v1/auth/me";
    public static final String AUTH_SIGNUP = "/api/v1/auth/signup";
    public static final String AUTH_LOGOUT = "/api/v1/auth/logout";
    public static final String AUTH_TERMS = "/api/v1/auth/terms";
    public static final String DEV_LOGIN = "/api/v1/dev/login";
    public static final String HEALTH = "/api/health";
    public static final String TERMS = "/api/v1/terms";
    public static final String USER_ID_HEADER = "X-Itda-User-Id";

    public static final String KAKAO_UNLINK_WEBHOOK = "/api/v1/webhooks/kakao/unlink";

    private ApiPaths() {
    }

    public static String pathOf(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
}
