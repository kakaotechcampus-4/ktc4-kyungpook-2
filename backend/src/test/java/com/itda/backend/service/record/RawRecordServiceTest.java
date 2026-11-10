package com.itda.backend.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.record.RawRecordUpload;
import com.itda.backend.exception.agent.AgentQueueFullException;
import com.itda.backend.exception.record.RawRecordEntryLimitException;
import com.itda.backend.exception.record.RawRecordNotFoundException;
import com.itda.backend.exception.record.RawRecordStorageException;
import com.itda.backend.exception.record.RawRecordUploadBusyException;
import com.itda.backend.exception.record.RawRecordValidationException;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.record.RawRecordUploadRepository;
import com.itda.backend.service.agent.AgentQueueGuard;
import com.itda.backend.service.storage.RawFileStorage;
import com.itda.backend.service.user.UserService;

@ExtendWith(MockitoExtension.class)
class RawRecordServiceTest {

    private static final String ORG_USER_ID = "1";
    private static final String OTHER_ORG_USER_ID = "2";
    private static final String PARENT_USER_ID = "3";
    private static final Long ORGANIZATION_ID = 7L;
    private static final Long UPLOAD_ID = 11L;
    private static final String STORED_PATH = "generated-uuid";

    @Mock
    private RawRecordRepository rawRecordRepository;

    private RawRecordRecorder rawRecordRecorder;

    @Mock
    private AgentQueueGuard agentQueueGuard;

    @Mock
    private RawRecordUploadRepository rawRecordUploadRepository;

    @Mock
    private RawFileStorage rawFileStorage;

    @Mock
    private UserService userService;

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private ChildRepository childRepository;

    private RawRecordService rawRecordService;

    private RawRecordUploadService uploadService;

    @BeforeEach
    void setUp() {
        lenient().when(childRepository.findByOrganizationId(any())).thenReturn(List.of());
        rawRecordRecorder = spy(new RawRecordRecorder(rawRecordRepository, journalEntryRepository, agentQueueGuard,
                rawRecordUploadRepository));
        rawRecordService = newService(PROPERTIES);
        uploadService = uploadServiceOf(rawRecordService);
    }

    private static final RawRecordProperties PROPERTIES = properties(500);

    private static RawRecordProperties properties(int maxEntriesPerFile) {
        return new RawRecordProperties(new RawRecordProperties.Limits(maxEntriesPerFile),
                new RawRecordProperties.Upload(2, 4, Duration.ofSeconds(10)));
    }

    private RawRecordService newService(RawRecordProperties properties) {
        return newService(properties, new RawRecordUploadGate(properties));
    }

    private RawRecordService newService(RawRecordProperties properties, RawRecordUploadGate gate) {
        return new RawRecordService(
                rawRecordRepository, userService, journalEntryRepository,
                new JournalEntrySplitter(), childRepository, new RawTextExtractor(), gate, properties);
    }

    private RawRecordUploadService uploadServiceOf(RawRecordService service) {
        return new RawRecordUploadService(service, rawRecordRecorder, rawRecordUploadRepository, rawRecordRepository,
                rawFileStorage, userService, agentQueueGuard,
                new DirectUploadProperties(Duration.ofMinutes(10), Duration.ofDays(1), DataSize.ofMegabytes(20)));
    }

    /** 저장소로 바로 올린 파일과 그 접수를 흉내 낸다. 완료 단계는 접수 · 저장된 크기 · 본문을 이것으로 읽는다. */
    private RawRecordUpload stage(MockMultipartFile file) throws Exception {
        RawRecordUpload upload = new RawRecordUpload(ORGANIZATION_ID, Long.valueOf(ORG_USER_ID),
                file.getOriginalFilename(), STORED_PATH, file.getContentType(), file.getSize());
        lenient().when(rawRecordUploadRepository.findById(UPLOAD_ID)).thenReturn(Optional.of(upload));
        lenient().when(rawRecordUploadRepository.findByIdForUpdate(UPLOAD_ID)).thenReturn(Optional.of(upload));
        lenient().when(rawFileStorage.sizeOf(STORED_PATH)).thenReturn(OptionalLong.of(file.getSize()));
        lenient().when(rawFileStorage.read(STORED_PATH)).thenAnswer(invocation -> file.getBytes());
        return upload;
    }

    private RawRecord complete(String userId, MockMultipartFile file) throws Exception {
        stage(file);
        return uploadService.complete(userId, UPLOAD_ID);
    }

    @Test
    void userWithoutOrganization_rejectedBeforeSaving() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());
        given(userService.getOrganizationIdOf(PARENT_USER_ID))
                .willThrow(new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED));

        assertThatThrownBy(() -> complete(PARENT_USER_ID, file))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(UserErrorCode.ORGANIZATION_NOT_ASSIGNED);

        verify(rawRecordRepository, never()).save(any());
    }

    @Test
    void unknownUser_rejectedBeforeSaving() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());
        given(userService.getOrganizationIdOf("9999"))
                .willThrow(new UserException(UserErrorCode.SESSION_USER_NOT_FOUND));

        assertThatThrownBy(() -> complete("9999", file))
                .isInstanceOf(UserException.class);

        verify(rawRecordRepository, never()).save(any());
    }

    @Test
    void storageReadFailure_convertedToStorageFailedException() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        stage(file);
        given(rawFileStorage.read(STORED_PATH)).willThrow(new java.io.IOException("s3 access denied"));

        assertThatThrownBy(() -> uploadService.complete(ORG_USER_ID, UPLOAD_ID))
                .isInstanceOf(RawRecordStorageException.class);

        verify(rawRecordRepository, never()).save(any());
    }

    @Test
    void dbSaveFailure_keepsUploadAndFileSoTheSameIdCanBeRetried() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willThrow(new RuntimeException("db unavailable"))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        RawRecordUpload upload = stage(file);

        assertThatThrownBy(() -> uploadService.complete(ORG_USER_ID, UPLOAD_ID))
                .isInstanceOf(RawRecordStorageException.class);

        assertThat(upload.isCompleted()).isFalse();
        verify(rawFileStorage, never()).delete(anyString());
        verify(rawRecordUploadRepository, never()).deleteById(any());

        RawRecord saved = uploadService.complete(ORG_USER_ID, UPLOAD_ID);

        assertThat(saved.getStoredPath()).isEqualTo(STORED_PATH);
        verify(rawRecordRepository, times(2)).save(any(RawRecord.class));
    }

    @Test
    void dbSaveSucceeds_doesNotDeleteStoredFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.csv", "text/csv", "a,b,c".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        RawRecord saved = complete(ORG_USER_ID, file);

        assertThat(saved.getStoredPath()).isEqualTo(STORED_PATH);
        assertThat(saved.getInstitutionId()).isEqualTo(String.valueOf(ORGANIZATION_ID));
        verify(rawFileStorage, never()).delete(anyString());
    }

    @Test
    void complete_splitsTextFileWithoutDateHeaderIntoSingleJournalEntry() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "그냥 관찰 문장 하나".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        complete(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("그냥 관찰 문장 하나");
        assertThat(captor.getValue().getSequenceNo()).isEqualTo(1);
    }

    @Test
    void complete_fillsEntryDateFromFilenameWhenContentHasNoDateHeader() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "0821_관찰일지.txt", "text/plain", "날짜 없는 관찰 문장".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        complete(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getEntryDate())
                .isEqualTo(LocalDate.of(LocalDate.now().getYear(), 8, 21));
    }

    @Test
    void complete_fillsEntryDateFromFilenameWithFullYear() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "2024-03-05_관찰일지.txt", "text/plain", "날짜 없는 관찰 문장".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        complete(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getEntryDate()).isEqualTo(LocalDate.of(2024, 3, 5));
    }

    @Test
    void complete_fallsBackToUploadDateWhenFilenameHasNoUsableDate() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "학생9977_기록.txt", "text/plain", "날짜 없는 관찰 문장".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        complete(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getEntryDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void complete_keepsDateFromContentEvenWhenFilenameHasDifferentDate() throws Exception {
        String content = "9/15 자유놀이를 했다.\n9/16 미술 시간.";
        MockMultipartFile file = new MockMultipartFile(
                "file", "0821_관찰일지.txt", "text/plain", content.getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        complete(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(JournalEntry::getEntryDate)
                .containsExactly(
                        LocalDate.of(LocalDate.now().getYear(), 9, 15),
                        LocalDate.of(LocalDate.now().getYear(), 9, 16));
    }

    @Test
    void complete_coverLineInheritsFirstDateInFileInsteadOfFilenameDate() throws Exception {
        String content = "표지: 8월 관찰일지\n9/15 자유놀이를 했다.\n9/16 미술 시간.";
        MockMultipartFile file = new MockMultipartFile(
                "file", "0821_관찰일지.txt", "text/plain", content.getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        complete(ORG_USER_ID, file);

        int thisYear = LocalDate.now().getYear();
        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(JournalEntry::getEntryDate)
                .containsExactly(
                        LocalDate.of(thisYear, 9, 15),
                        LocalDate.of(thisYear, 9, 15),
                        LocalDate.of(thisYear, 9, 16));
    }

    @Test
    void complete_fillsEntryDateFromKoreanFilenameFormat() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "8월21일_관찰일지.txt", "text/plain", "날짜 없는 관찰 문장".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        complete(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getEntryDate())
                .isEqualTo(LocalDate.of(LocalDate.now().getYear(), 8, 21));
    }

    @Test
    void complete_neverSavesJournalEntryWithNullEntryDate() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "메모.txt", "text/plain", "표지\n내용만 있고 날짜는 없음".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        complete(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).isNotEmpty();
        assertThat(captor.getAllValues()).extracting(JournalEntry::getEntryDate).doesNotContainNull();
    }

    @Test
    void complete_splitsTextFileWithDateHeadersIntoMultipleJournalEntries() throws Exception {
        String content = "9/15 자유놀이 중 블록을 높이 쌓았다.\n9/16 미술 시간에 그림을 완성함.";
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", content.getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        complete(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository, times(2)).save(captor.capture());
        List<JournalEntry> saved = captor.getAllValues();
        assertThat(saved.get(0).getContent()).isEqualTo("자유놀이 중 블록을 높이 쌓았다.");
        assertThat(saved.get(0).getEntryDate().getMonthValue()).isEqualTo(9);
        assertThat(saved.get(0).getEntryDate().getDayOfMonth()).isEqualTo(15);
        assertThat(saved.get(1).getContent()).isEqualTo("미술 시간에 그림을 완성함.");
    }

    @Test
    void complete_splitsDocxTableAndDropsPreambleBeforeFirstDate() throws Exception {
        byte[] docx = RawTextExtractorTest.docx(
                List.of("관찰일지", "아동: 김하늘 / 생년월일: 2018-03-02"),
                List.of(List.of("생년월일", "2018-03-02", ""),
                        List.of("날짜", "출처기관", "관찰 내용"),
                        List.of("2026-09-15", "센터", "손을 잡고 30까지 세자 편안해짐"),
                        List.of("2026-09-16", "센터", "블록을 혼자 쌓음")));
        MockMultipartFile file = new MockMultipartFile("file", "note.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        complete(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository, times(2)).save(captor.capture());
        List<JournalEntry> saved = captor.getAllValues();
        assertThat(saved.get(0).getEntryDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(saved.get(0).getContent()).isEqualTo("센터 손을 잡고 30까지 세자 편안해짐");
        assertThat(saved.get(1).getEntryDate()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(saved).noneMatch(entry -> entry.getContent().contains("생년월일"));
    }

    @Test
    void complete_acceptsDocxSentAsOctetStream() throws Exception {
        byte[] docx = RawTextExtractorTest.docx(List.of("2026-09-15 블록을 혼자 쌓음"), List.of());
        MockMultipartFile file = new MockMultipartFile("file", "note.docx", "application/octet-stream", docx);
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        complete(ORG_USER_ID, file);

        verify(journalEntryRepository).save(any());
    }

    @Test
    void complete_rejectsOctetStreamForNonDocx() {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "application/octet-stream", "x".getBytes());
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        assertThatThrownBy(() -> complete(ORG_USER_ID, file))
                .isInstanceOf(RawRecordValidationException.class);
    }

    @Test
    void complete_keepsWholeDocxWhenItHasNoDateHeader() throws Exception {
        byte[] docx = RawTextExtractorTest.docx(List.of("오늘 블록을 혼자 쌓았다."), List.of());
        MockMultipartFile file = new MockMultipartFile("file", "note.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        complete(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("오늘 블록을 혼자 쌓았다.");
    }

    @Test
    void complete_skipsSplittingWhenTextCannotBeExtracted() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.pdf", "application/pdf", "%PDF-1.4 fake".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        complete(ORG_USER_ID, file);

        verify(journalEntryRepository, never()).save(any());
    }

    @Test
    void complete_acceptsWindowsExcelCsvContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.csv", "application/vnd.ms-excel", "a,b,c".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        RawRecord saved = complete(ORG_USER_ID, file);

        assertThat(saved).isNotNull();
    }

    @Test
    void complete_journalEntrySaveFailure_keepsUploadAndFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "관찰 문장".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(journalEntryRepository.save(any())).willThrow(new RuntimeException("db unavailable"));
        RawRecordUpload upload = stage(file);

        assertThatThrownBy(() -> uploadService.complete(ORG_USER_ID, UPLOAD_ID))
                .isInstanceOf(RawRecordStorageException.class);

        assertThat(upload.isCompleted()).isFalse();
        verify(rawFileStorage, never()).delete(anyString());
    }

    private static MockMultipartFile datedTxt(int n) {
        StringBuilder text = new StringBuilder();
        LocalDate day = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < n; i++) {
            text.append(day.plusDays(i)).append(" 관찰 ").append(i).append('\n');
        }
        return new MockMultipartFile("file", "note.txt", "text/plain",
                text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void complete_rejectsFileWithTooManyEntriesBeforeSaving() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        assertThatThrownBy(() -> complete(ORG_USER_ID, datedTxt(501)))
                .isInstanceOf(RawRecordEntryLimitException.class);

        verify(rawRecordRepository, never()).save(any());
        verify(rawRecordRepository, never()).save(any());
        verify(journalEntryRepository, never()).save(any());
    }

    @Test
    void complete_rejectsDocxWithPreambleAndTooManyEntries() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        List<String> paragraphs = new java.util.ArrayList<>(List.of("관찰일지", "아동명 김하늘"));
        for (int i = 0; i < 501; i++) {
            paragraphs.add(LocalDate.of(2026, 1, 1).plusDays(i) + " 관찰 " + i);
        }
        MockMultipartFile file = new MockMultipartFile("file", "note.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                RawTextExtractorTest.docx(paragraphs, List.of()));

        assertThatThrownBy(() -> complete(ORG_USER_ID, file))
                .isInstanceOf(RawRecordEntryLimitException.class);
    }

    @Test
    void complete_acceptsFileAtEntryLimit() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(rawRecordRepository.save(any(RawRecord.class))).willAnswer(invocation -> invocation.getArgument(0));

        complete(ORG_USER_ID, datedTxt(500));

        verify(journalEntryRepository, times(500)).save(any());
        verify(agentQueueGuard).peek(ORGANIZATION_ID, 500);
        verify(agentQueueGuard).requireRoom(ORGANIZATION_ID, 500);
    }

    @Test
    void complete_rejectsEntryLongerThanMaxBeforeSaving() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        String longEntry = "가".repeat(JournalEntry.MAX_CONTENT_LENGTH + 1);
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                longEntry.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> complete(ORG_USER_ID, file))
                .isInstanceOf(RawRecordEntryLimitException.class);

        verify(rawRecordRepository, never()).save(any());
    }

    @Test
    void complete_acceptsEntryAtMaxLength() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(rawRecordRepository.save(any(RawRecord.class))).willAnswer(invocation -> invocation.getArgument(0));
        String entry = "가".repeat(JournalEntry.MAX_CONTENT_LENGTH);
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                entry.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        complete(ORG_USER_ID, file);

        verify(journalEntryRepository).save(any());
    }

    @Test
    void complete_queueFullOnPeek_rejectsBeforeSaving() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        org.mockito.BDDMockito.willThrow(new AgentQueueFullException(ORGANIZATION_ID, 1000, 3, 1000))
                .given(agentQueueGuard).peek(ORGANIZATION_ID, 3);

        assertThatThrownBy(() -> complete(ORG_USER_ID, datedTxt(3)))
                .isInstanceOf(AgentQueueFullException.class);

        verify(rawRecordRepository, never()).save(any());
        verify(rawFileStorage, never()).delete(anyString());
    }

    @Test
    void complete_queueFullInsideLock_keepsUploadAndFileAndSavesNothing() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        org.mockito.BDDMockito.willThrow(new AgentQueueFullException(ORGANIZATION_ID, 999, 3, 1000))
                .given(agentQueueGuard).requireRoom(ORGANIZATION_ID, 3);

        assertThatThrownBy(() -> complete(ORG_USER_ID, datedTxt(3)))
                .isInstanceOf(AgentQueueFullException.class);

        verify(rawFileStorage, never()).delete(anyString());
        verify(rawRecordRepository, never()).save(any());
    }

    @Test
    void complete_unreadableFile_asksForNoRoom() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.pdf", "application/pdf", "%PDF-1.4 fake".getBytes());
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(rawRecordRepository.save(any(RawRecord.class))).willAnswer(invocation -> invocation.getArgument(0));

        complete(ORG_USER_ID, file);

        verify(agentQueueGuard).requireRoom(ORGANIZATION_ID, 0);
        verify(journalEntryRepository, never()).save(any());
    }

    @Test
    void complete_uploadGateFull_rejectsBeforeReadingAndStorage() throws Exception {
        RawRecordProperties oneSlot = new RawRecordProperties(new RawRecordProperties.Limits(500),
                new RawRecordProperties.Upload(1, 0, Duration.ofSeconds(10)));
        RawRecordUploadGate gate = new RawRecordUploadGate(oneSlot);
        RawRecordService service = newService(oneSlot, gate);
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        stage(datedTxt(1));

        try (RawRecordUploadGate.Slot held = gate.acquire()) {
            assertThatThrownBy(() -> uploadServiceOf(service).complete(ORG_USER_ID, UPLOAD_ID))
                    .isInstanceOf(RawRecordUploadBusyException.class);
        }

        verify(rawFileStorage, never()).read(anyString());
        verify(rawRecordRepository, never()).save(any());
    }

    @Test
    void complete_appliesHintWhenExactlyOneRosterNameInFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "0821_박서연_관찰일지.txt", "text/plain", "오늘 있었던 일".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(childRepository.findByOrganizationId(ORGANIZATION_ID))
                .willReturn(List.of(Child.of("박서연", LocalDate.of(2019, 5, 5))));

        RawRecord saved = complete(ORG_USER_ID, file);

        assertThat(saved.getHintName()).isEqualTo("박서연");
        assertThat(saved.getHintBirthdate()).isEqualTo(LocalDate.of(2019, 5, 5));
    }

    @Test
    void complete_leavesHintBlankWhenNoRosterNameMatches() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "8월_3주차_활동.txt", "text/plain", "오늘 있었던 일".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(childRepository.findByOrganizationId(ORGANIZATION_ID))
                .willReturn(List.of(Child.of("박서연", LocalDate.of(2019, 5, 5))));

        RawRecord saved = complete(ORG_USER_ID, file);

        assertThat(saved.getHintName()).isNull();
    }

    @Test
    void complete_leavesHintBlankWhenRosterHasHomonyms() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "0821_김민준_관찰일지.txt", "text/plain", "오늘 있었던 일".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(childRepository.findByOrganizationId(ORGANIZATION_ID))
                .willReturn(List.of(
                        Child.of("김민준", LocalDate.of(2019, 1, 1)),
                        Child.of("김민준", LocalDate.of(2020, 2, 2))));

        RawRecord saved = complete(ORG_USER_ID, file);

        assertThat(saved.getHintName()).isNull();
    }

    @Test
    void complete_keepsLongestMatchWhenShorterNameIsSubstringOfLongerName() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "0821_박서연_관찰일지.txt", "text/plain", "오늘 있었던 일".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(childRepository.findByOrganizationId(ORGANIZATION_ID))
                .willReturn(List.of(
                        Child.of("서연", LocalDate.of(2018, 3, 3)),
                        Child.of("박서연", LocalDate.of(2019, 5, 5))));

        RawRecord saved = complete(ORG_USER_ID, file);

        assertThat(saved.getHintName()).isEqualTo("박서연");
    }

    @Test
    void complete_matchesHintEvenWhenFilenameIsNfdNormalized() throws Exception {
        String nfdFilename = Normalizer.normalize("0821_박서연_관찰일지.txt", Normalizer.Form.NFD);
        MockMultipartFile file = new MockMultipartFile("file", nfdFilename, "text/plain", "오늘 있었던 일".getBytes());
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(childRepository.findByOrganizationId(ORGANIZATION_ID))
                .willReturn(List.of(Child.of("박서연", LocalDate.of(2019, 5, 5))));

        RawRecord saved = complete(ORG_USER_ID, file);

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

    @Test
    void getByInstitution_scopedToCallersOrganization() {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(rawRecordRepository.findByInstitutionIdAndDeletedAtIsNull(String.valueOf(ORGANIZATION_ID)))
                .willReturn(java.util.List.of());

        assertThat(rawRecordService.getByInstitution(ORG_USER_ID)).isEmpty();

        verify(rawRecordRepository).findByInstitutionIdAndDeletedAtIsNull(String.valueOf(ORGANIZATION_ID));
    }
}
