package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.exception.RawRecordNotFoundException;
import com.itda.backend.exception.RawRecordStorageException;
import com.itda.backend.exception.RawRecordValidationException;
import com.itda.backend.repository.RawRecordRepository;
import com.itda.backend.service.storage.RawFileStorage;

@ExtendWith(MockitoExtension.class)
class RawRecordServiceTest {

    @Mock
    private RawRecordRepository rawRecordRepository;

    @Mock
    private RawFileStorage rawFileStorage;

    private RawRecordService rawRecordService;

    @BeforeEach
    void setUp() {
        rawRecordService = new RawRecordService(rawRecordRepository, rawFileStorage);
    }

    @Test
    void institutionIdTooLong_rejectedBeforeStorage() throws Exception {
        String tooLong = "x".repeat(256);
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());

        assertThatThrownBy(() -> rawRecordService.ingest(tooLong, file))
                .isInstanceOf(RawRecordValidationException.class);

        verify(rawFileStorage, never()).store(any(), anyString());
    }

    @Test
    void dbSaveFailure_keepsStoredFileAndRecordsFailedStatus() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.csv");
        // 첫 저장 시도는 실패, 두 번째(FAILED 상태 기록) 시도는 성공한다고 가정.
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willThrow(new RuntimeException("db unavailable"))
                .willAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> rawRecordService.ingest("inst-1", file))
                .isInstanceOf(RawRecordStorageException.class);

        // append-only — 이미 저장소에 올라간 원본 파일은 지우지 않는다.
        verify(rawFileStorage, never()).delete(anyString());

        ArgumentCaptor<RawRecord> captor = ArgumentCaptor.forClass(RawRecord.class);
        verify(rawRecordRepository, times(2)).save(captor.capture());
        RawRecord failedRecord = captor.getAllValues().get(1);
        assertThat(failedRecord.getStatus()).isEqualTo(RawRecordStatus.FAILED);
        assertThat(failedRecord.getStoredPath()).isEqualTo("generated-uuid.csv");
    }

    @Test
    void dbSaveSucceeds_doesNotDeleteStoredFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.csv");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        RawRecord saved = rawRecordService.ingest("inst-1", file);

        assertThat(saved.getStoredPath()).isEqualTo("generated-uuid.csv");
        verify(rawFileStorage, never()).delete(anyString());
    }

    @Test
    void getById_ownedByCaller_returnsRecord() {
        RawRecord record = new RawRecord(
                "kakao-1", "note.csv", "stored.csv", "text/csv", 10L, RawRecordStatus.PENDING);
        given(rawRecordRepository.findById(1L)).willReturn(Optional.of(record));

        RawRecord found = rawRecordService.getById(1L, "kakao-1");

        assertThat(found).isSameAs(record);
    }

    @Test
    void getById_ownedByAnotherInstitution_returnsNotFound() {
        RawRecord record = new RawRecord(
                "kakao-1", "note.csv", "stored.csv", "text/csv", 10L, RawRecordStatus.PENDING);
        given(rawRecordRepository.findById(1L)).willReturn(Optional.of(record));

        assertThatThrownBy(() -> rawRecordService.getById(1L, "kakao-attacker"))
                .isInstanceOf(RawRecordNotFoundException.class);
    }
}
