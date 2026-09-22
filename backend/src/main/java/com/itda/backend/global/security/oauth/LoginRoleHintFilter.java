package com.itda.backend.global.security.oauth;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.itda.backend.domain.UserRole;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 로그인 진입 경로로 역할을 정하기 위해, 진입할 때 붙은 초대 코드를 세션에 적어둔다.
 *
 * <p>카카오에 다녀오면 우리가 아는 건 "카카오 회원번호 하나가 로그인했다" 뿐이라,
 * 이 사람이 초대 링크로 들어온 보호자인지 그냥 로그인한 기관 담당자인지 구분할 단서가 없다.
 * 그 단서를 진입 시점에 세션에 남겨 성공 핸들러가 읽게 한다.
 *
 * <p>보관소로 세션을 쓰는 이유: oauth2Login 이 state 를 담으려고 이미 세션을 만든다.
 * 같은 세션에 얹으면 힌트와 state 의 수명이 정확히 같아지고, 성공·실패 핸들러가
 * 세션을 버릴 때 힌트도 함께 사라진다. 쿠키를 따로 만들 필요가 없다.
 *
 * <p>파라미터 이름은 {@code code} 가 아니라 {@code invite} 다 —
 * 카카오 콜백이 인가 코드를 {@code code} 로 보내기 때문에 이름이 겹치면 안 된다.
 *
 * <p>초대 코드가 어떤 아이·어떤 기관을 가리키는지는 아직 해석하지 않는다.
 * 지금은 "붙어 있는가" 만 역할 결정에 쓴다.
 */
@Component
public class LoginRoleHintFilter extends OncePerRequestFilter {

    public static final String INVITE_PARAM = "invite";
    public static final String ROLE_SESSION_ATTRIBUTE = "ITDA_LOGIN_ROLE";

    private static final String AUTHORIZATION_PATH = "/oauth2/authorization/";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (isLoginEntryPoint(request)) {
            rememberRoleHint(request);
        }
        filterChain.doFilter(request, response);
    }

    private boolean isLoginEntryPoint(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith(AUTHORIZATION_PATH);
    }

    private void rememberRoleHint(HttpServletRequest request) {
        if (StringUtils.hasText(request.getParameter(INVITE_PARAM))) {
            request.getSession(true).setAttribute(ROLE_SESSION_ATTRIBUTE, UserRole.PARENT);
            return;
        }
        // 초대 코드 없이 들어왔다. 앞선 시도에서 남은 힌트가 있으면 지운다 —
        // 그냥 두면 중간에 그만둔 보호자 로그인의 흔적이 다음 기관 로그인에 묻어난다.
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ROLE_SESSION_ATTRIBUTE);
        }
    }
}
