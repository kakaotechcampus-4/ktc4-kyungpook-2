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
import com.itda.backend.exception.UserErrorCode;
import com.itda.backend.exception.UserException;
import com.itda.backend.repository.RawRecordRepository;
import com.itda.backend.service.storage.RawFileStorage;

@ExtendWith(MockitoExtension.class)
class RawRecordServiceTest {

    /** principal 은 내부 userId 다. 소속 기관 id 와 다른 값이어야 둘이 섞이는 실수를 잡는다. */
    private static final String ORG_USER_ID = "1";
    private static final String OTHER_ORG_USER_ID = "2";
    private static final String PARENT_USER_ID = "3";
    private static final Long ORGANIZATION_ID = 7L;

    @Mock
    private RawRecordRepository rawRecordRepository;

    @Mock
    private RawFileStorage rawFileStorage;

    @Mock
    private UserService userService;

    private RawRecordService rawRecordService;

    @BeforeEach
    void setUp() {
        rawRecordService = new RawRecordService(rawRecordRepository, rawFileStorage, userService);
    }

    /** 보호자처럼 기관 소속이 아닌 사용자는 파일이 저장소에 올라가기 전에 막혀야 한다. */
    @Test
    void userWithoutOrganization_rejectedBeforeStorage() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());
        given(userService.getOrganizationIdOf(PARENT_USER_ID))
                .willThrow(new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED));

        assertThatThrownBy(() -> rawRecordService.ingest(PARENT_USER_ID, file))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.ORGANIZATION_NOT_ASSIGNED);

        verify(rawFileStorage, never()).store(any(), anyString());
    }

    /** 토큰은 유효한데 사용자 행이 없는 경우(옛 토큰)도 저장소를 건드리지 않는다. */
    @Test
    void unknownUser_rejectedBeforeStorage() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());
        given(userService.getOrganizationIdOf("9999"))
                .willThrow(new UserException(UserErrorCode.SESSION_USER_NOT_FOUND));

        assertThatThrownBy(() -> rawRecordService.ingest("9999", file))
                .isInstanceOf(UserException.class);

        verify(rawFileStorage, never()).store(any(), anyString());
    }

    @Test
    void storageThrowsRuntimeException_convertedToStorageFailedException() throws Exception {
        // S3RawFileStorage.store()는 인증/네트워크 오류 시 IOException이 아니라
        // SdkException(unchecked RuntimeException)을 던질 수 있다 — 이것도 도메인 예외로 변환돼야 한다.
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(rawFileStorage.store(any(), anyString())).willThrow(new RuntimeException("s3 access denied"));

        assertThatThrownBy(() -> rawRecordService.ingest(ORG_USER_ID, file))
                .isInstanceOf(RawRecordStorageException.class);

        verify(rawRecordRepository, never()).save(any());
    }

    @Test
    void dbSaveFailure_keepsStoredFileAndRecordsFailedStatus() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.csv");
        // 첫 저장 시도는 실패, 두 번째(FAILED 상태 기록) 시도는 성공한다고 가정.
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willThrow(new RuntimeException("db unavailable"))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        assertThatThrownBy(() -> rawRecordService.ingest(ORG_USER_ID, file))
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
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        RawRecord saved = rawRecordService.ingest(ORG_USER_ID, file);

        assertThat(saved.getStoredPath()).isEqualTo("generated-uuid.csv");
        // 카카오 회원번호가 아니라 사용자의 소속 기관 id 로 기록된다.
        assertThat(saved.getInstitutionId()).isEqualTo(String.valueOf(ORGANIZATION_ID));
        verify(rawFileStorage, never()).delete(anyString());
    }

    @Test
    void getById_ownedByCaller_returnsRecord() {
        RawRecord record = new RawRecord(
                String.valueOf(ORGANIZATION_ID), "note.csv", "stored.csv", "text/csv", 10L,
                RawRecordStatus.PENDING);
        given(rawRecordRepository.findById(1L)).willReturn(Optional.of(record));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        RawRecord found = rawRecordService.getById(1L, ORG_USER_ID);

        assertThat(found).isSameAs(record);
    }

    @Test
    void getById_ownedByAnotherInstitution_returnsNotFound() {
        RawRecord record = new RawRecord(
                String.valueOf(ORGANIZATION_ID), "note.csv", "stored.csv", "text/csv", 10L,
                RawRecordStatus.PENDING);
        given(rawRecordRepository.findById(1L)).willReturn(Optional.of(record));
        given(userService.getOrganizationIdOf(OTHER_ORG_USER_ID)).willReturn(99L);

        assertThatThrownBy(() -> rawRecordService.getById(1L, OTHER_ORG_USER_ID))
                .isInstanceOf(RawRecordNotFoundException.class);
    }

    /** 목록도 호출자의 소속 기관으로만 좁혀져야 한다. */
    @Test
    void getByInstitution_scopedToCallersOrganization() {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(rawRecordRepository.findByInstitutionId(String.valueOf(ORGANIZATION_ID)))
                .willReturn(java.util.List.of());

        assertThat(rawRecordService.getByInstitution(ORG_USER_ID)).isEmpty();

        verify(rawRecordRepository).findByInstitutionId(String.valueOf(ORGANIZATION_ID));
    }
}
