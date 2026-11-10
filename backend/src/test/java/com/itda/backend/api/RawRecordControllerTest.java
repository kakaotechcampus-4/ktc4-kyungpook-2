package com.itda.backend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.itda.backend.service.record.RawRecordUploadService;
import com.itda.backend.service.record.RecordProgressService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.exception.agent.AgentQueueFullException;
import com.itda.backend.exception.record.RawRecordEntryLimitException;
import com.itda.backend.exception.record.RawRecordNotFoundException;
import com.itda.backend.exception.record.RawRecordUploadBusyException;
import com.itda.backend.exception.record.RawRecordValidationException;
import com.itda.backend.service.record.RawRecordService;

@WebMvcTest(RawRecordController.class)
@AutoConfigureMockMvc(addFilters = false)
class RawRecordControllerTest {

    private static final String BASE_URL = "/api/v1/raw-records";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RawRecordService rawRecordService;

    @MockitoBean
    private RecordProgressService recordProgressService;

    @MockitoBean
    private RawRecordUploadService rawRecordUploadService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String institutionId) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(institutionId, null, List.of()));
    }

    @Test
    void uploadValidFile_returns201() throws Exception {
        authenticateAs("kakao-1");
        RawRecord saved = new RawRecord(
                "kakao-1", "note.csv", "generated-uuid.csv", "text/csv", 10L, RawRecordStatus.PENDING);
        given(rawRecordService.ingest(anyString(), any())).willReturn(saved);

        MockMultipartFile file =
                new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());

        mockMvc.perform(multipart(BASE_URL).file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.institutionId").value("kakao-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void uploadInvalidExtension_returns400() throws Exception {
        authenticateAs("kakao-1");
        given(rawRecordService.ingest(anyString(), any()))
                .willThrow(new RawRecordValidationException("unsupported file extension"));

        MockMultipartFile file =
                new MockMultipartFile("file", "malware.exe", "application/octet-stream", "x".getBytes());

        mockMvc.perform(multipart(BASE_URL).file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("RAW_RECORD_INVALID_REQUEST"));
    }

    @Test
    void uploadTooManyEntries_returns400WithEntryLimitCode() throws Exception {
        authenticateAs("kakao-1");
        given(rawRecordService.ingest(anyString(), any()))
                .willThrow(new RawRecordEntryLimitException("too many journal entries in file: 501 > 500"));

        mockMvc.perform(multipart(BASE_URL).file(new MockMultipartFile("file", "note.txt", "text/plain", "x".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("RAW_RECORD_ENTRY_LIMIT_EXCEEDED"));
    }

    @Test
    void uploadWhileBusy_returns429WithUploadBusyCode() throws Exception {
        authenticateAs("kakao-1");
        given(rawRecordService.ingest(anyString(), any()))
                .willThrow(new RawRecordUploadBusyException("upload slots and waiting queue are full"));

        mockMvc.perform(multipart(BASE_URL).file(new MockMultipartFile("file", "note.txt", "text/plain", "x".getBytes())))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("RAW_RECORD_UPLOAD_BUSY"));
    }

    @Test
    void uploadWhenAgentQueueFull_returns429WithQueueFullCode() throws Exception {
        authenticateAs("kakao-1");
        given(rawRecordService.ingest(anyString(), any()))
                .willThrow(new AgentQueueFullException(1L, 1000, 3, 1000));

        mockMvc.perform(multipart(BASE_URL).file(new MockMultipartFile("file", "note.txt", "text/plain", "x".getBytes())))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("AGENT_QUEUE_FULL"));
    }

    @Test
    void getUnknownId_returns404() throws Exception {
        authenticateAs("kakao-1");
        given(rawRecordService.getById(999L, "kakao-1")).willThrow(new RawRecordNotFoundException(999L));

        mockMvc.perform(get(BASE_URL + "/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("RAW_RECORD_NOT_FOUND"));
    }

    @Test
    void getOtherInstitutionsRecord_returns404NotForbidden() throws Exception {
        authenticateAs("kakao-attacker");
        given(rawRecordService.getById(1L, "kakao-attacker")).willThrow(new RawRecordNotFoundException(1L));

        mockMvc.perform(get(BASE_URL + "/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listByInstitution_scopedToAuthenticatedPrincipal() throws Exception {
        authenticateAs("kakao-1");
        given(rawRecordService.getByInstitution("kakao-1")).willReturn(List.of());

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data").isArray());
    }
}
