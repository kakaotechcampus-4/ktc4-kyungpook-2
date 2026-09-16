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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.exception.RawRecordNotFoundException;
import com.itda.backend.exception.RawRecordValidationException;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.RawRecordService;

// addFilters(false)라 필터 체인이 안 돈다 — JwtAuthenticationFilter가 실제로 하는 일
// (SecurityContext principal에 카카오ID(String)를 심는 것)을 authenticateAs(...)로 직접
// 재현한다. RequestPostProcessor 기반의 authentication(...)은 세션에만 값을 넣고 그걸
// SecurityContextHolder로 옮기는 건 필터가 하는 일이라, addFilters(false)에서는 무시된다.
@WebMvcTest(RawRecordController.class)
@AutoConfigureMockMvc(addFilters = false)
class RawRecordControllerTest {

    private static final String BASE_URL = "/api/v1/raw-records";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RawRecordService rawRecordService;

    @MockitoBean
    private JwtProvider jwtProvider;

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
        // 다른 기관 소유 레코드를 조회하면, 서비스 계층이 "권한 없음"이 아니라 "없음"으로 던진다
        // (RawRecordService.getById 참고) — 존재 여부 자체를 노출하지 않기 위함.
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
