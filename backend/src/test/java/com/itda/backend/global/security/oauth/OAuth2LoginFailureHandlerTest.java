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

@ExtendWith(OutputCaptureExtension.class)
class OAuth2LoginFailureHandlerTest {

    private static final String CODE = "N3UCxk8aQ2";

    private final OAuth2LoginFailureHandler handler = new OAuth2LoginFailureHandler("http://localhost:3000/login");

    @Test
    void 카카오가_돌려준_인가_코드는_로그에서_가린다(CapturedOutput output) throws IOException {
        OAuth2Error error = new OAuth2Error("invalid_grant",
                "authorization code not found for code=" + CODE + "," + CODE, null);
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(error, error.toString());

        handler.onAuthenticationFailure(new MockHttpServletRequest(), new MockHttpServletResponse(), exception);

        assertThat(output).doesNotContain(CODE);
        assertThat(output).contains("카카오 로그인 실패", "invalid_grant", "authorization code not found for code=***");
    }

    @Test
    void 이름이_code_로_끝나는_다른_값은_가리지_않는다(CapturedOutput output) throws IOException {
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
