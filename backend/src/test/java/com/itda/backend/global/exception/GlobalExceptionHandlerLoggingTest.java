package com.itda.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.api.ChildController;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.ChildService;

/**
 * 잘못된 요청(4xx) 로그에 사용자가 보낸 값이 남지 않는지 실제 출력으로 확인한다.
 *
 * <p>검증 실패·본문 해석 실패의 예외 메시지에는 거부된 값이나 입력 일부가 들어갈 수 있다. 로그에는 예외 종류·상태
 * 코드·필드 이름만 남아야 한다. 로그가 아예 꺼져서 통과하는 일이 없도록 남아야 할 정보도 함께 확인한다.
 */
@WebMvcTest(controllers = ChildController.class,
        properties = "logging.level.com.itda.backend.global.exception=DEBUG")
// MockMvc 기본 출력은 요청 본문을 콘솔에 찍어 로그 검사를 오염시키므로 끈다.
@AutoConfigureMockMvc(addFilters = false, print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerLoggingTest {

    private static final String CHILDREN = "/api/v1/institutions/me/children";
    private static final String SECRET = "S3CR3T-INPUT";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChildService childService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private JwtCookie jwtCookie;

    private void postBadRequest(String body) throws Exception {
        mockMvc.perform(post(CHILDREN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validationFailureLogsFieldAndCodeButNotRejectedValue(CapturedOutput output) throws Exception {
        String tooLongName = SECRET + "x".repeat(100);

        postBadRequest("{\"name\": \"%s\", \"birthDate\": \"2017-03-14\"}".formatted(tooLongName));

        assertThat(output).doesNotContain(SECRET);
        assertThat(output).contains("MethodArgumentNotValidException", "400", "name:Size");
    }

    /** 본문이 도중에 끊기면 필드 경로가 나온다는 보장이 없으니 경로는 확인하지 않는다. */
    @Test
    void brokenJsonLogsExceptionKindButNotInput(CapturedOutput output) throws Exception {
        postBadRequest("{\"externalId\": \"%s\", \"name\": ".formatted(SECRET));

        assertThat(output).doesNotContain(SECRET);
        assertThat(output).contains("HttpMessageNotReadableException", "400");
    }

    @Test
    void fieldTypeMismatchLogsFieldPathButNotInput(CapturedOutput output) throws Exception {
        postBadRequest("{\"name\": {\"value\": \"%s\"}, \"birthDate\": \"2017-03-14\"}".formatted(SECRET));

        assertThat(output).doesNotContain(SECRET);
        assertThat(output).contains("HttpMessageNotReadableException", "400", "path=name");
    }

    /** 없는 경로(404) 메시지에는 요청 경로가 그대로 들어간다("No static resource ..."). 경로도 사용자 입력이다. */
    @Test
    void unknownPathLogsExceptionKindButNotRequestedPath(CapturedOutput output) throws Exception {
        mockMvc.perform(get(CHILDREN + "/" + SECRET)).andExpect(status().isNotFound());

        assertThat(output).doesNotContain(SECRET);
        assertThat(output).contains("NoResourceFoundException", "404");
    }
}
