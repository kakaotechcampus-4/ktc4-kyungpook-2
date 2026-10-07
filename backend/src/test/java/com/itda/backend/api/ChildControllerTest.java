package com.itda.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.itda.backend.domain.ChildStatus;
import com.itda.backend.dto.ChildRosterResponse;
import com.itda.backend.dto.request.RegisterChildRequest;
import com.itda.backend.dto.response.RegisteredChildResponse;
import com.itda.backend.dto.response.RegisteredChildResponse.RegisteredChild;
import com.itda.backend.exception.UserErrorCode;
import com.itda.backend.exception.UserException;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.ChildService;

@WebMvcTest(ChildController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChildControllerTest {

    private static final String BASE_URL = "/api/v1/institutions/me/children";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChildService childService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private JwtCookie jwtCookie;

    @Test
    void getRoster_returnsChildren() throws Exception {
        given(childService.getRoster(any()))
                .willReturn(List.of(new ChildRosterResponse("1", "김하늘", "2020-01-01", ChildStatus.ACTIVE)));

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].name").value("김하늘"))
                .andExpect(jsonPath("$.data[0].status").value("active"));
    }

    private ResultActions register(String body) throws Exception {
        return mockMvc.perform(post(BASE_URL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void givenRegistered() {
        given(childService.register(any(), any())).willReturn(new RegisteredChildResponse(
                new RegisteredChild("9", "김하늘", "2017-03-14", ChildStatus.PENDING_CONSENT)));
    }

    @Test
    void register_returnsCreatedChildWrappedInChild() throws Exception {
        givenRegistered();

        register("""
                {"name": "김하늘", "birthDate": "2017-03-14"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.child.id").value("9"))
                .andExpect(jsonPath("$.data.child.name").value("김하늘"))
                .andExpect(jsonPath("$.data.child.birthDate").value("2017-03-14"))
                .andExpect(jsonPath("$.data.child.status").value("pending_consent"));
    }

    @Test
    void register_passesStrippedNameAndParsedBirthDateToService() throws Exception {
        givenRegistered();

        register("""
                {"name": "  김하늘  ", "birthDate": "2017-03-14"}
                """)
                .andExpect(status().isCreated());

        ArgumentCaptor<RegisterChildRequest> captor = ArgumentCaptor.forClass(RegisterChildRequest.class);
        verify(childService).register(any(), captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("김하늘");
        assertThat(captor.getValue().birthDateValue()).isEqualTo(LocalDate.of(2017, 3, 14));
    }

    /** 기관 내부 ID·연락처 뒤 4자리는 이번 범위에서 받지 않는다. 보내더라도 무시하고 등록한다. */
    @Test
    void register_ignoresFieldsOutsideThisScope() throws Exception {
        givenRegistered();

        register("""
                {"name": "김하늘", "birthDate": "2017-03-14", "externalId": "2026-0031", "guardianPhoneLast4": "1234"}
                """)
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"birthDate\": \"2017-03-14\"}",
            "{\"name\": null, \"birthDate\": \"2017-03-14\"}",
            "{\"name\": \"\", \"birthDate\": \"2017-03-14\"}",
            "{\"name\": \"   \", \"birthDate\": \"2017-03-14\"}"})
    void register_rejectsMissingOrBlankName(String body) throws Exception {
        expectInvalidRequest(body);
    }

    @Test
    void register_rejectsNameLongerThan100AfterStrip() throws Exception {
        expectInvalidRequest("{\"name\": \"%s\", \"birthDate\": \"2017-03-14\"}".formatted("가".repeat(101)));
    }

    @Test
    void register_acceptsNameOf100CharsSurroundedBySpaces() throws Exception {
        givenRegistered();

        register("{\"name\": \"  %s  \", \"birthDate\": \"2017-03-14\"}".formatted("가".repeat(100)))
                .andExpect(status().isCreated());
    }

    /** 날짜는 "yyyy-MM-dd" 문자열만 받는다. 날짜시각·배열·숫자처럼 Jackson 이 LocalDate 로 바꿔줄 입력도 거부한다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\": \"김하늘\"}",
            "{\"name\": \"김하늘\", \"birthDate\": null}",
            "{\"name\": \"김하늘\", \"birthDate\": \"\"}",
            "{\"name\": \"김하늘\", \"birthDate\": \"2017/03/14\"}",
            "{\"name\": \"김하늘\", \"birthDate\": \"2017-3-14\"}",
            "{\"name\": \"김하늘\", \"birthDate\": \"2017-03-14T00:00:00\"}",
            "{\"name\": \"김하늘\", \"birthDate\": [2017, 3, 14]}",
            "{\"name\": \"김하늘\", \"birthDate\": 20170314}",
            "{\"name\": \"김하늘\", \"birthDate\": \"2017-02-30\"}",
            "{\"name\": \"김하늘\", \"birthDate\": \"2017-13-01\"}"})
    void register_rejectsBirthDateThatIsNotAStrictCalendarDate(String body) throws Exception {
        expectInvalidRequest(body);
    }

    @Test
    void register_rejectsFutureBirthDate() throws Exception {
        expectInvalidRequest("{\"name\": \"김하늘\", \"birthDate\": \"%s\"}".formatted(LocalDate.now().plusDays(1)));
    }

    @Test
    void register_acceptsBirthDateOfToday() throws Exception {
        givenRegistered();

        register("{\"name\": \"김하늘\", \"birthDate\": \"%s\"}".formatted(LocalDate.now()))
                .andExpect(status().isCreated());
    }

    @Test
    void register_returnsForbiddenWhenUserHasNoOrganization() throws Exception {
        given(childService.register(any(), any()))
                .willThrow(new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED));

        register("""
                {"name": "김하늘", "birthDate": "2017-03-14"}
                """)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
    }

    private void expectInvalidRequest(String body) throws Exception {
        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(childService, never()).register(any(), any());
    }
}
