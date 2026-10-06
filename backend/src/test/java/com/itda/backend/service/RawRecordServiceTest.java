package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
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

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.exception.RawRecordNotFoundException;
import com.itda.backend.exception.RawRecordStorageException;
import com.itda.backend.exception.RawRecordValidationException;
import com.itda.backend.exception.UserErrorCode;
import com.itda.backend.exception.UserException;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.JournalEntryRepository;
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
    private RawRecordRecorder rawRecordRecorder;

    @Mock
    private RawFileStorage rawFileStorage;

    @Mock
    private UserService userService;

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private ChildRepository childRepository;

    private RawRecordService rawRecordService;

    @BeforeEach
    void setUp() {
        // 대부분의 테스트는 힌트 매칭과 무관하니 기본값(빈 명부)을 깔아둔다 — 명부가 없으면
        // 힌트는 항상 비워진다(applyHintFromFilename). 힌트 자체를 검증하는 테스트는 이 stub을 덮어쓴다.
        lenient().when(childRepository.findByOrganizationId(any())).thenReturn(List.of());
        // JournalEntrySplitter는 의존성 없는 순수 로직이라 목 대신 실제 구현을 쓴다.
        rawRecordService = new RawRecordService(
                rawRecordRepository, rawRecordRecorder, rawFileStorage, userService, journalEntryRepository,
                new JournalEntrySplitter(), childRepository);
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

        verify(rawRecordRecorder, never()).save(any());
    }

    @Test
    void dbSaveFailure_keepsStoredFileAndRecordsFailedStatus() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.csv");
        given(rawRecordRecorder.save(any(RawRecord.class))).willThrow(new RuntimeException("db unavailable"));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        assertThatThrownBy(() -> rawRecordService.ingest(ORG_USER_ID, file))
                .isInstanceOf(RawRecordStorageException.class);

        // append-only — 이미 저장소에 올라간 원본 파일은 지우지 않는다.
        verify(rawFileStorage, never()).delete(anyString());

        // 실패 기록은 별도 트랜잭션(REQUIRES_NEW)으로 분리된 RawRecordRecorder.recordFailure가 남긴다.
        verify(rawRecordRecorder).recordFailure(
                String.valueOf(ORGANIZATION_ID), "note.csv", "generated-uuid.csv", "text/csv", 5L);
    }

    @Test
    void dbSaveSucceeds_doesNotDeleteStoredFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.csv");
        given(rawRecordRecorder.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        RawRecord saved = rawRecordService.ingest(ORG_USER_ID, file);

        assertThat(saved.getStoredPath()).isEqualTo("generated-uuid.csv");
        // 카카오 회원번호가 아니라 사용자의 소속 기관 id 로 기록된다.
        assertThat(saved.getInstitutionId()).isEqualTo(String.valueOf(ORGANIZATION_ID));
        verify(rawFileStorage, never()).delete(anyString());
    }

    /** 날짜 헤더 없는 txt/csv는 전체가 기록 1건으로 저장된다. */
    @Test
    void ingest_splitsTextFileWithoutDateHeaderIntoSingleJournalEntry() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "그냥 관찰 문장 하나".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRecorder.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("그냥 관찰 문장 하나");
        assertThat(captor.getValue().getSequenceNo()).isEqualTo(1);
    }

    /** 날짜 헤더가 있으면 헤더마다 기록이 나뉘고, 각 기록의 날짜도 채워진다. */
    @Test
    void ingest_splitsTextFileWithDateHeadersIntoMultipleJournalEntries() throws Exception {
        String content = "9/15 자유놀이 중 블록을 높이 쌓았다.\n9/16 미술 시간에 그림을 완성함.";
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", content.getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRecorder.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository, times(2)).save(captor.capture());
        List<JournalEntry> saved = captor.getAllValues();
        assertThat(saved.get(0).getContent()).isEqualTo("자유놀이 중 블록을 높이 쌓았다.");
        assertThat(saved.get(0).getEntryDate().getMonthValue()).isEqualTo(9);
        assertThat(saved.get(0).getEntryDate().getDayOfMonth()).isEqualTo(15);
        assertThat(saved.get(1).getContent()).isEqualTo("미술 시간에 그림을 완성함.");
    }

    /** pdf처럼 아직 텍스트를 못 뽑는 형식은 기록 분리를 건너뛴다 — entries가 비는 게 정상이다. */
    @Test
    void ingest_skipsSplittingForNonExtractableExtension() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.pdf", "application/pdf", "%PDF-1.4 fake".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.pdf");
        given(rawRecordRecorder.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        verify(journalEntryRepository, never()).save(any());
    }

    /** 기록 분리 중 저장이 실패해도 업로드 응답(반환값)은 그대로 성공이어야 한다. */
    @Test
    void ingest_journalEntrySaveFailure_doesNotFailUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "관찰 문장".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRecorder.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(journalEntryRepository.save(any())).willThrow(new RuntimeException("db unavailable"));

        RawRecord saved = rawRecordService.ingest(ORG_USER_ID, file);

        assertThat(saved.getStoredPath()).isEqualTo("generated-uuid.txt");
    }

    /** 파일명에 명부 아동 이름이 정확히 한 명 포함되면 표지 힌트로 채운다. */
    @Test
    void ingest_appliesHintWhenExactlyOneRosterNameInFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "0821_박서연_관찰일지.txt", "text/plain", "오늘 있었던 일".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRecorder.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(childRepository.findByOrganizationId(ORGANIZATION_ID))
                .willReturn(List.of(Child.of("박서연", LocalDate.of(2019, 5, 5))));

        RawRecord saved = rawRecordService.ingest(ORG_USER_ID, file);

        assertThat(saved.getHintName()).isEqualTo("박서연");
        assertThat(saved.getHintBirthdate()).isEqualTo(LocalDate.of(2019, 5, 5));
    }

    /** 파일명에 명부 이름이 하나도 없으면 힌트를 비워둔다 — 억지로 채우지 않는다. */
    @Test
    void ingest_leavesHintBlankWhenNoRosterNameMatches() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "8월_3주차_활동.txt", "text/plain", "오늘 있었던 일".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRecorder.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(childRepository.findByOrganizationId(ORGANIZATION_ID))
                .willReturn(List.of(Child.of("박서연", LocalDate.of(2019, 5, 5))));

        RawRecord saved = rawRecordService.ingest(ORG_USER_ID, file);

        assertThat(saved.getHintName()).isNull();
    }

    /**
     * 명부에 동명이인이 있으면(같은 이름 두 명) 어느 아이인지 특정할 수 없다 — 틀린 힌트가
     * 힌트 없는 것보다 나쁘므로(AI가 명부에 없는 이름으로 오인해 unmatched로 떨어뜨림) 비워둔다.
     */
    @Test
    void ingest_leavesHintBlankWhenRosterHasHomonyms() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "0821_김민준_관찰일지.txt", "text/plain", "오늘 있었던 일".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRecorder.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(childRepository.findByOrganizationId(ORGANIZATION_ID))
                .willReturn(List.of(
                        Child.of("김민준", LocalDate.of(2019, 1, 1)),
                        Child.of("김민준", LocalDate.of(2020, 2, 2))));

        RawRecord saved = rawRecordService.ingest(ORG_USER_ID, file);

        assertThat(saved.getHintName()).isNull();
    }

    /** "서연"이 "박서연"의 부분 문자열이면, 짧은 이름 말고 가장 긴 매칭만 힌트로 남긴다. */
    @Test
    void ingest_keepsLongestMatchWhenShorterNameIsSubstringOfLongerName() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "0821_박서연_관찰일지.txt", "text/plain", "오늘 있었던 일".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRecorder.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(childRepository.findByOrganizationId(ORGANIZATION_ID))
                .willReturn(List.of(
                        Child.of("서연", LocalDate.of(2018, 3, 3)),
                        Child.of("박서연", LocalDate.of(2019, 5, 5))));

        RawRecord saved = rawRecordService.ingest(ORG_USER_ID, file);

        assertThat(saved.getHintName()).isEqualTo("박서연");
    }

    /** macOS는 한글 파일명을 NFD(자모 분리)로 준다 — NFC로 정규화하지 않으면 눈엔 같아도 매칭이 실패한다. */
    @Test
    void ingest_matchesHintEvenWhenFilenameIsNfdNormalized() throws Exception {
        String nfdFilename = Normalizer.normalize("0821_박서연_관찰일지.txt", Normalizer.Form.NFD);
        MockMultipartFile file = new MockMultipartFile("file", nfdFilename, "text/plain", "오늘 있었던 일".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRecorder.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(childRepository.findByOrganizationId(ORGANIZATION_ID))
                .willReturn(List.of(Child.of("박서연", LocalDate.of(2019, 5, 5))));

        RawRecord saved = rawRecordService.ingest(ORG_USER_ID, file);

        assertThat(saved.getHintName()).isEqualTo("박서연");
    }

    @Test
    void getById_ownedByCaller_returnsRecord() {
        RawRecord record = new RawRecord(
                String.valueOf(ORGANIZATION_ID), "note.csv", "stored.csv", "text/csv", 10L,
                RawRecordStatus.PENDING);
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(record));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        RawRecord found = rawRecordService.getById(1L, ORG_USER_ID);

        assertThat(found).isSameAs(record);
    }

    @Test
    void getById_ownedByAnotherInstitution_returnsNotFound() {
        RawRecord record = new RawRecord(
                String.valueOf(ORGANIZATION_ID), "note.csv", "stored.csv", "text/csv", 10L,
                RawRecordStatus.PENDING);
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(record));
        given(userService.getOrganizationIdOf(OTHER_ORG_USER_ID)).willReturn(99L);

        assertThatThrownBy(() -> rawRecordService.getById(1L, OTHER_ORG_USER_ID))
                .isInstanceOf(RawRecordNotFoundException.class);
    }

    /** 목록도 호출자의 소속 기관으로만 좁혀져야 한다. */
    @Test
    void getByInstitution_scopedToCallersOrganization() {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(rawRecordRepository.findByInstitutionIdAndDeletedAtIsNull(String.valueOf(ORGANIZATION_ID)))
                .willReturn(java.util.List.of());

        assertThat(rawRecordService.getByInstitution(ORG_USER_ID)).isEmpty();

        verify(rawRecordRepository).findByInstitutionIdAndDeletedAtIsNull(String.valueOf(ORGANIZATION_ID));
    }
}
