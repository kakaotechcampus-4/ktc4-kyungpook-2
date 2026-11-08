package com.itda.backend.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * 로그인 실패 로그에 카카오 인가 코드가 남지 않는지 실제 출력으로 확인한다 (#193).
 *
 * <p>카카오는 이미 썼거나 만료된 인가 코드로 토큰을 요청하면 KOE320 오류 설명에 그 코드를 그대로 담아 보낸다
 * ({@code "authorization code not found for code=..."}). Spring Security 는 그 설명을 예외 메시지로 만들고,
 * 핸들러가 그 메시지를 로그에 남긴다. 원인 설명은 남기고 코드 값만 가린다.
 */
@ExtendWith(OutputCaptureExtension.class)
class OAuth2LoginFailureHandlerTest {

    private static final String CODE = "N3UCxk8aQ2";

    private final OAuth2LoginFailureHandler handler = new OAuth2LoginFailureHandler("http://localhost:3000/login");

    @Test
    void 카카오가_돌려준_인가_코드는_로그에서_가린다(CapturedOutput output) throws IOException {
        // 카카오 응답 원문 형식. 요청에 code 가 두 번 붙으면 쉼표로 이어서 두 번 돌려준다.
        OAuth2Error error = new OAuth2Error("invalid_grant",
                "authorization code not found for code=" + CODE + "," + CODE, null);
        // OAuth2AuthorizationCodeAuthenticationProvider 가 만드는 모양 그대로
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(error, error.toString());

        handler.onAuthenticationFailure(new MockHttpServletRequest(), new MockHttpServletResponse(), exception);

        assertThat(output).doesNotContain(CODE);
        // 로그가 아예 꺼져서 통과하지 않도록, 원인은 남아 있는지도 본다.
        assertThat(output).contains("카카오 로그인 실패", "invalid_grant", "authorization code not found for code=***");
    }

    @Test
    void 이름이_code_로_끝나는_다른_값은_가리지_않는다(CapturedOutput output) throws IOException {
        // 장애 원인을 찾는 단서라 남아야 한다.
        OAuth2Error error = new OAuth2Error("invalid_request", "error_code=KOE006 status_code=400", null);

        handler.onAuthenticationFailure(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new OAuth2AuthenticationException(error, error.toString()));

        assertThat(output).contains("error_code=KOE006", "status_code=400");
    }

    @Test
    void 메시지가_없는_실패도_로그를_남기고_로그인_화면으로_보낸다(CapturedOutput output) throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response, new BadCredentialsException(null));

        assertThat(output).contains("카카오 로그인 실패");
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/login?error=login_failed");
    }
}
