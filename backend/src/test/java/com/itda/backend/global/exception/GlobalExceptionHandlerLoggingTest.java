package com.itda.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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
import com.itda.backend.api.GuardianController;
import com.itda.backend.exception.invitation.InvitationErrorCode;
import com.itda.backend.exception.invitation.InvitationException;
import com.itda.backend.service.child.ChildService;
import com.itda.backend.service.child.GuardianChildQueryService;
import com.itda.backend.service.invitation.ChildGuardianService;
import com.itda.backend.service.invitation.InvitationService;
import com.itda.backend.service.sharing.GuardianInstitutionShareService;

@WebMvcTest(controllers = {ChildController.class, GuardianController.class},
        properties = "logging.level.com.itda.backend.global.exception=DEBUG")
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
    private InvitationService invitationService;

    @MockitoBean
    private ChildGuardianService childGuardianService;

    @MockitoBean
    private GuardianChildQueryService guardianChildQueryService;

    @MockitoBean
    private GuardianInstitutionShareService guardianInstitutionShareService;

    private void postBadRequest(String body) throws Exception {
        mockMvc.perform(post(CHILDREN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validationFailureLogsFieldAndCodeButNotRejectedValue(CapturedOutput output) throws Exception {
        String tooLongName = SECRET + "x".repeat(100);

        postBadRequest("{\"name\": \"%s\", \"birthDate\": \"2017-03-14\", \"externalId\": \"A-1\"}".formatted(tooLongName));

        assertThat(output).doesNotContain(SECRET);
        assertThat(output).contains("MethodArgumentNotValidException", "400", "name:Size");
    }

    @Test
    void brokenJsonLogsExceptionKindButNotInput(CapturedOutput output) throws Exception {
        postBadRequest("{\"externalId\": \"%s\", \"name\": ".formatted(SECRET));

        assertThat(output).doesNotContain(SECRET);
        assertThat(output).contains("HttpMessageNotReadableException", "400");
    }

    @Test
    void fieldTypeMismatchLogsFieldPathButNotInput(CapturedOutput output) throws Exception {
        postBadRequest("{\"name\": {\"value\": \"%s\"}, \"birthDate\": \"2017-03-14\", \"externalId\": \"A-1\"}".formatted(SECRET));

        assertThat(output).doesNotContain(SECRET);
        assertThat(output).contains("HttpMessageNotReadableException", "400", "path=name");
    }

    @Test
    void unknownPathLogsExceptionKindButNotRequestedPath(CapturedOutput output) throws Exception {
        mockMvc.perform(get(CHILDREN + "/" + SECRET + "/nothing")).andExpect(status().isNotFound());

        assertThat(output).doesNotContain(SECRET);
        assertThat(output).contains("NoResourceFoundException", "404");
    }

    @Test
    void invalidInviteCodeLogsFixedMessageButNotCode(CapturedOutput output) throws Exception {
        given(invitationService.accept(any(), any()))
                .willThrow(new InvitationException(InvitationErrorCode.INVALID_INVITE_CODE));

        mockMvc.perform(post("/api/v1/guardians/me/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(SECRET)))
                .andExpect(status().isBadRequest());

        assertThat(output).doesNotContain(SECRET);
        assertThat(output).contains("요청 처리 실패", "INVALID_INVITE_CODE");
    }

    @Test
    void businessExceptionReasonIsLoggedOnOneLine(CapturedOutput output) throws Exception {
        given(invitationService.accept(any(), any()))
                .willThrow(new BusinessException(InvitationErrorCode.INVALID_INVITE_CODE,
                        "reason\nFAKE LOG LINE\r end"));

        mockMvc.perform(post("/api/v1/guardians/me/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"ABC\"}"))
                .andExpect(status().isBadRequest());

        assertThat(output).contains("reason=reason_FAKE LOG LINE__end");
        assertThat(output).doesNotContain("\nFAKE LOG LINE");
    }
}
