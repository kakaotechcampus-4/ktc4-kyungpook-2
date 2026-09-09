package com.itda.backend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.exception.RawRecordNotFoundException;
import com.itda.backend.exception.RawRecordValidationException;
import com.itda.backend.service.RawRecordService;

@WebMvcTest(RawRecordController.class)
class RawRecordControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RawRecordService rawRecordService;

    @Test
    void uploadValidFile_returns201() throws Exception {
        RawRecord saved = new RawRecord(
                "inst-1", "note.csv", "generated-uuid.csv", "text/csv", 10L, RawRecordStatus.PENDING);
        given(rawRecordService.ingest(anyString(), any())).willReturn(saved);

        MockMultipartFile file =
                new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());

        mockMvc.perform(multipart("/api/raw-records").file(file).param("institutionId", "inst-1"))
                .andExpect(status().isCreated());
    }

    @Test
    void uploadInvalidExtension_returns400() throws Exception {
        given(rawRecordService.ingest(anyString(), any()))
                .willThrow(new RawRecordValidationException("unsupported file extension"));

        MockMultipartFile file =
                new MockMultipartFile("file", "malware.exe", "application/octet-stream", "x".getBytes());

        mockMvc.perform(multipart("/api/raw-records").file(file).param("institutionId", "inst-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadMissingInstitutionId_returns400() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());

        mockMvc.perform(multipart("/api/raw-records").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getUnknownId_returns404() throws Exception {
        given(rawRecordService.getById(999L)).willThrow(new RawRecordNotFoundException(999L));

        mockMvc.perform(get("/api/raw-records/999")).andExpect(status().isNotFound());
    }

    @Test
    void listByInstitution_returns200() throws Exception {
        given(rawRecordService.getByInstitution("inst-1")).willReturn(List.of());

        mockMvc.perform(get("/api/raw-records").param("institutionId", "inst-1"))
                .andExpect(status().isOk());
    }
}
