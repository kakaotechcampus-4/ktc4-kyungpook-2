package com.itda.backend.api;

import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 엔드포인트는 더 이상 없다. Spring Security 가
 * {@code /oauth2/authorization/kakao} 와 {@code /login/oauth2/code/kakao} 를 직접 제공한다.
 *
 * <p>남은 건 로그아웃뿐이다. 출입증이 httpOnly 쿠키로 바뀌면서 프론트가 스스로 지울 수
 * 없게 됐기 때문에, 서버가 지워주지 않으면 사용자는 로그아웃할 방법이 아예 없다.
 * (localStorage 시절에는 프론트가 그냥 지우면 됐다.)
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final JwtCookie jwtCookie;

    public AuthController(JwtCookie jwtCookie) {
        this.jwtCookie = jwtCookie;
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie.expire().toString());
        return ApiResponse.success();
    }
}
