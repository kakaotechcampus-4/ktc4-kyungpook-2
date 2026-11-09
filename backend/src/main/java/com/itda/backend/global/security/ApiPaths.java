package com.itda.backend.global.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.util.UrlPathHelper;

/**
 * 보안 필터들이 경로로 판단할 때 쓰는 경로와 판정. 필터마다 따로 적으면 같은 경로를 다르게 읽는다.
 */
public final class ApiPaths {

    public static final String API_PREFIX = "/api/";

    public static final String AUTH_ME = "/api/v1/auth/me";
    public static final String AUTH_SIGNUP = "/api/v1/auth/signup";
    public static final String AUTH_LOGOUT = "/api/v1/auth/logout";
    public static final String AUTH_TERMS = "/api/v1/auth/terms";
    public static final String DEV_LOGIN = "/api/v1/dev/login";
    public static final String HEALTH = "/api/health";
    /** 약관 본문. 공개 문서라 로그인 · 가입 · 약관 동의 없이 연다 */
    public static final String TERMS = "/api/v1/terms";
    /**
     * 응답을 만든 회원의 id 를 싣는 헤더. 보호자 아이 목록 응답에 붙는다 — 화면이 보여 주는 아이 목록과 탈퇴 대상 계정을 같은
     * 응답에서 꺼내, 다른 탭에서 계정이 바뀐 직후에도 둘이 다른 계정을 가리키지 않게 한다.
     */
    public static final String USER_ID_HEADER = "X-Itda-User-Id";

    /** 카카오 연결 해제 웹훅. 로그인 · CSRF 대신 어드민 키 헤더로 검증한다 */
    public static final String KAKAO_UNLINK_WEBHOOK = "/api/v1/webhooks/kakao/unlink";

    private ApiPaths() {
    }

    /**
     * {@code getRequestURI()} 는 디코딩 전 값이라 "/%61pi/..." 가 "/api/" 로 시작하지 않는 것으로 보인다.
     * 컨트롤러 매핑과 같은 기준인, 디코딩하고 ";" 파라미터를 뗀 애플리케이션 내부 경로로 판단한다.
     */
    public static String pathOf(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
}
