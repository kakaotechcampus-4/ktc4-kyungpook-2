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

import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
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
import com.itda.backend.service.agent.AgentQueueGuard;
import com.itda.backend.service.storage.RawFileStorage;
import com.itda.backend.service.user.UserService;

@ExtendWith(MockitoExtension.class)
class RawRecordServiceTest {

    /** principal 은 내부 userId 다. 소속 기관 id 와 다른 값이어야 둘이 섞이는 실수를 잡는다. */
    private static final String ORG_USER_ID = "1";
    private static final String OTHER_ORG_USER_ID = "2";
    private static final String PARENT_USER_ID = "3";
    private static final Long ORGANIZATION_ID = 7L;

    @Mock
    private RawRecordRepository rawRecordRepository;

    /** 원본 행 · 일지 저장은 실제 레코더로 한다 — 저장소(목)에 무엇을 남겼는지로 본다. 실패 기록 호출은 spy 로 본다. */
    private RawRecordRecorder rawRecordRecorder;

    @Mock
    private AgentQueueGuard agentQueueGuard;

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
        rawRecordRecorder = spy(new RawRecordRecorder(rawRecordRepository, journalEntryRepository, agentQueueGuard));
        rawRecordService = newService(PROPERTIES);
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
                rawRecordRepository, rawRecordRecorder, rawFileStorage, userService, journalEntryRepository,
                new JournalEntrySplitter(), childRepository, new RawTextExtractor(), gate, agentQueueGuard,
                properties);
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
        given(rawRecordRepository.save(any(RawRecord.class))).willThrow(new RuntimeException("db unavailable"));
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
        given(rawRecordRepository.save(any(RawRecord.class)))
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
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("그냥 관찰 문장 하나");
        assertThat(captor.getValue().getSequenceNo()).isEqualTo(1);
    }

    // --- entry_date 채우기 -------------------------------------------------------
    // 요약은 아동 × 날짜로 묶이므로(DB 스키마 §8.2) entry_date 가 비면 그 기록은 요약에서 통째로 빠진다.
    // 본문에서 날짜를 못 찾으면 파일명 → 업로드 날짜 순으로 내려가며 채운다.

    /** 본문에 날짜 헤더가 없으면 파일명의 MMDD 를 쓴다. 연도는 알 수 없어 업로드 연도로 둔다. */
    @Test
    void ingest_fillsEntryDateFromFilenameWhenContentHasNoDateHeader() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "0821_관찰일지.txt", "text/plain", "날짜 없는 관찰 문장".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getEntryDate())
                .isEqualTo(LocalDate.of(LocalDate.now().getYear(), 8, 21));
    }

    /** 파일명에 네 자리 연도가 있으면 그 연도를 그대로 믿는다. */
    @Test
    void ingest_fillsEntryDateFromFilenameWithFullYear() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "2024-03-05_관찰일지.txt", "text/plain", "날짜 없는 관찰 문장".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getEntryDate()).isEqualTo(LocalDate.of(2024, 3, 5));
    }

    /** 파일명 숫자가 날짜로 성립하지 않으면(학번 등) 업로드 날짜로 내려간다. */
    @Test
    void ingest_fallsBackToUploadDateWhenFilenameHasNoUsableDate() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "학생9977_기록.txt", "text/plain", "날짜 없는 관찰 문장".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getEntryDate()).isEqualTo(LocalDate.now());
    }

    /** 본문에서 읽은 날짜가 있으면 파일명 날짜로 덮어쓰지 않는다 — 본문이 항상 우선이다. */
    @Test
    void ingest_keepsDateFromContentEvenWhenFilenameHasDifferentDate() throws Exception {
        String content = "9/15 자유놀이를 했다.\n9/16 미술 시간.";
        MockMultipartFile file = new MockMultipartFile(
                "file", "0821_관찰일지.txt", "text/plain", content.getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(JournalEntry::getEntryDate)
                .containsExactly(
                        LocalDate.of(LocalDate.now().getYear(), 9, 15),
                        LocalDate.of(LocalDate.now().getYear(), 9, 16));
    }

    /**
     * 표지처럼 날짜 헤더 앞에 있는 줄은 같은 파일의 첫 날짜를 물려받는다(코드리뷰 반영).
     *
     * <p>파일명 날짜를 주면 한 파일이 8/21·9/15·9/16 세 묶음으로 흩어진다 — 요약이 아동 × 날짜 ×
     * 기관으로 묶이므로 같은 파일이 쪼개지면 안 된다.
     */
    @Test
    void ingest_coverLineInheritsFirstDateInFileInsteadOfFilenameDate() throws Exception {
        String content = "표지: 8월 관찰일지\n9/15 자유놀이를 했다.\n9/16 미술 시간.";
        MockMultipartFile file = new MockMultipartFile(
                "file", "0821_관찰일지.txt", "text/plain", content.getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        int thisYear = LocalDate.now().getYear();
        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(JournalEntry::getEntryDate)
                .containsExactly(
                        LocalDate.of(thisYear, 9, 15),
                        LocalDate.of(thisYear, 9, 15),
                        LocalDate.of(thisYear, 9, 16));
    }

    /** 파일명이 한글 날짜 표기여도 읽는다 — 기관에서 흔한 형식이다. */
    @Test
    void ingest_fillsEntryDateFromKoreanFilenameFormat() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "8월21일_관찰일지.txt", "text/plain", "날짜 없는 관찰 문장".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getEntryDate())
                .isEqualTo(LocalDate.of(LocalDate.now().getYear(), 8, 21));
    }

    /** 어떤 경로로 들어오든 entry_date 가 빈 기록은 더 이상 저장되지 않는다. */
    @Test
    void ingest_neverSavesJournalEntryWithNullEntryDate() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "메모.txt", "text/plain", "표지\n내용만 있고 날짜는 없음".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).isNotEmpty();
        assertThat(captor.getAllValues()).extracting(JournalEntry::getEntryDate).doesNotContainNull();
    }

    /** 날짜 헤더가 있으면 헤더마다 기록이 나뉘고, 각 기록의 날짜도 채워진다. */
    @Test
    void ingest_splitsTextFileWithDateHeadersIntoMultipleJournalEntries() throws Exception {
        String content = "9/15 자유놀이 중 블록을 높이 쌓았다.\n9/16 미술 시간에 그림을 완성함.";
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", content.getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRepository.save(any(RawRecord.class)))
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

    /** docx 일지는 표의 날짜 칸으로 나누고, 첫 날짜 앞 머리말(이름·생년월일)은 기록으로 만들지 않는다 (#383). */
    @Test
    void ingest_splitsDocxTableAndDropsPreambleBeforeFirstDate() throws Exception {
        byte[] docx = RawTextExtractorTest.docx(
                List.of("관찰일지", "아동: 김하늘 / 생년월일: 2018-03-02"),
                List.of(List.of("생년월일", "2018-03-02", ""),
                        List.of("날짜", "출처기관", "관찰 내용"),
                        List.of("2026-09-15", "센터", "손을 잡고 30까지 세자 편안해짐"),
                        List.of("2026-09-16", "센터", "블록을 혼자 쌓음")));
        MockMultipartFile file = new MockMultipartFile("file", "note.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.docx");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository, times(2)).save(captor.capture());
        List<JournalEntry> saved = captor.getAllValues();
        assertThat(saved.get(0).getEntryDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(saved.get(0).getContent()).isEqualTo("센터 손을 잡고 30까지 세자 편안해짐");
        assertThat(saved.get(1).getEntryDate()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(saved).noneMatch(entry -> entry.getContent().contains("생년월일"));
    }

    /** Office 가 없는 PC 는 docx 를 application/octet-stream 으로 보낸다 — docx 일 때만 받아 준다. */
    @Test
    void ingest_acceptsDocxSentAsOctetStream() throws Exception {
        byte[] docx = RawTextExtractorTest.docx(List.of("2026-09-15 블록을 혼자 쌓음"), List.of());
        MockMultipartFile file = new MockMultipartFile("file", "note.docx", "application/octet-stream", docx);
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.docx");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        verify(journalEntryRepository).save(any());
    }

    /** octet-stream 예외는 docx 에만 준다 — 다른 확장자는 여전히 막는다. */
    @Test
    void ingest_rejectsOctetStreamForNonDocx() {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "application/octet-stream", "x".getBytes());
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        assertThatThrownBy(() -> rawRecordService.ingest(ORG_USER_ID, file))
                .isInstanceOf(RawRecordValidationException.class);
    }

    /** 날짜가 하나도 없는 docx 는 머리말을 버리지 않는다 — 버리면 기록이 통째로 사라진다. */
    @Test
    void ingest_keepsWholeDocxWhenItHasNoDateHeader() throws Exception {
        byte[] docx = RawTextExtractorTest.docx(List.of("오늘 블록을 혼자 쌓았다."), List.of());
        MockMultipartFile file = new MockMultipartFile("file", "note.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.docx");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("오늘 블록을 혼자 쌓았다.");
    }

    /** 텍스트를 못 뽑는 파일(깨진 pdf)은 업로드는 두고 기록 분리만 건너뛴다 — entries가 비는 게 정상이다. */
    @Test
    void ingest_skipsSplittingWhenTextCannotBeExtracted() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.pdf", "application/pdf", "%PDF-1.4 fake".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.pdf");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        rawRecordService.ingest(ORG_USER_ID, file);

        verify(journalEntryRepository, never()).save(any());
    }

    /** Windows가 csv를 저장소에서 바로 올릴 때 보내는 content-type도 거부하지 않아야 한다(#113). */
    @Test
    void ingest_acceptsWindowsExcelCsvContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.csv", "application/vnd.ms-excel", "a,b,c".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.csv");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        RawRecord saved = rawRecordService.ingest(ORG_USER_ID, file);

        assertThat(saved).isNotNull();
    }

    /**
     * 일지 저장이 실패하면 업로드도 실패한다 (#424). 원본 행과 일지가 한 트랜잭션이라 원본 행도 남지 않고, 파일은 저장소에 둔 채
     * FAILED 행으로 추적한다 — 예전에는 일지 0건으로 성공시켰다.
     */
    @Test
    void ingest_journalEntrySaveFailure_failsUploadAndRecordsFailedStatus() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "관찰 문장".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRepository.save(any(RawRecord.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(journalEntryRepository.save(any())).willThrow(new RuntimeException("db unavailable"));

        assertThatThrownBy(() -> rawRecordService.ingest(ORG_USER_ID, file))
                .isInstanceOf(RawRecordStorageException.class);

        verify(rawRecordRecorder).recordFailure(
                String.valueOf(ORGANIZATION_ID), "note.txt", "generated-uuid.txt", "text/plain", file.getSize());
        verify(rawFileStorage, never()).delete(anyString());
    }

    // --- 상한 (#424) ----------------------------------------------------------------

    /** 날짜 헤더 n 개짜리 txt. 하루에 한 건씩 n 개의 일지로 나뉜다. */
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
    void ingest_rejectsFileWithTooManyEntriesBeforeStorage() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);

        assertThatThrownBy(() -> rawRecordService.ingest(ORG_USER_ID, datedTxt(501)))
                .isInstanceOf(RawRecordEntryLimitException.class);

        verify(rawFileStorage, never()).store(any(), anyString());
        verify(rawRecordRepository, never()).save(any());
        verify(journalEntryRepository, never()).save(any());
    }

    /** 문서 머리말(첫 날짜 앞 한 건)을 빼도 상한을 넘으면 거절한다 — 나누기를 상한에서 멈춰도 잘린 채 받지 않는다. */
    @Test
    void ingest_rejectsDocxWithPreambleAndTooManyEntries() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        List<String> paragraphs = new java.util.ArrayList<>(List.of("관찰일지", "아동명 김하늘"));
        for (int i = 0; i < 501; i++) {
            paragraphs.add(LocalDate.of(2026, 1, 1).plusDays(i) + " 관찰 " + i);
        }
        MockMultipartFile file = new MockMultipartFile("file", "note.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                RawTextExtractorTest.docx(paragraphs, List.of()));

        assertThatThrownBy(() -> rawRecordService.ingest(ORG_USER_ID, file))
                .isInstanceOf(RawRecordEntryLimitException.class);
    }

    @Test
    void ingest_acceptsFileAtEntryLimit() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRepository.save(any(RawRecord.class))).willAnswer(invocation -> invocation.getArgument(0));

        rawRecordService.ingest(ORG_USER_ID, datedTxt(500));

        verify(journalEntryRepository, times(500)).save(any());
        // 잠금 안에서 다시 센다 — 들어올 수는 나뉜 일지 수다.
        verify(agentQueueGuard).peek(ORGANIZATION_ID, 500);
        verify(agentQueueGuard).requireRoom(ORGANIZATION_ID, 500);
    }

    @Test
    void ingest_rejectsEntryLongerThanMaxBeforeStorage() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        String longEntry = "가".repeat(JournalEntry.MAX_CONTENT_LENGTH + 1);
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                longEntry.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> rawRecordService.ingest(ORG_USER_ID, file))
                .isInstanceOf(RawRecordEntryLimitException.class);

        verify(rawFileStorage, never()).store(any(), anyString());
    }

    @Test
    void ingest_acceptsEntryAtMaxLength() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRepository.save(any(RawRecord.class))).willAnswer(invocation -> invocation.getArgument(0));
        String entry = "가".repeat(JournalEntry.MAX_CONTENT_LENGTH);
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                entry.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        rawRecordService.ingest(ORG_USER_ID, file);

        verify(journalEntryRepository).save(any());
    }

    /** 잠그지 않고 미리 센 값으로 넘치면 저장소에 올리지도 않는다. */
    @Test
    void ingest_queueFullOnPeek_rejectsBeforeStorage() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        org.mockito.BDDMockito.willThrow(new AgentQueueFullException(ORGANIZATION_ID, 1000, 3, 1000))
                .given(agentQueueGuard).peek(ORGANIZATION_ID, 3);

        assertThatThrownBy(() -> rawRecordService.ingest(ORG_USER_ID, datedTxt(3)))
                .isInstanceOf(AgentQueueFullException.class);

        verify(rawFileStorage, never()).store(any(), anyString());
    }

    /** 미리 보기는 통과했는데 잠금 안에서 넘치면, 이미 올린 파일을 지우고 아무 행도 남기지 않는다. */
    @Test
    void ingest_queueFullInsideLock_deletesStoredFileAndSavesNothing() throws Exception {
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        org.mockito.BDDMockito.willThrow(new AgentQueueFullException(ORGANIZATION_ID, 999, 3, 1000))
                .given(agentQueueGuard).requireRoom(ORGANIZATION_ID, 3);

        assertThatThrownBy(() -> rawRecordService.ingest(ORG_USER_ID, datedTxt(3)))
                .isInstanceOf(AgentQueueFullException.class);

        verify(rawFileStorage).delete("generated-uuid.txt");
        verify(rawRecordRepository, never()).save(any());
        verify(rawRecordRecorder, never()).recordFailure(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    /** 글자를 못 뽑은 파일은 들어올 일지가 0건이다 — 자리를 막지 않는다. */
    @Test
    void ingest_unreadableFile_asksForNoRoom() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.pdf", "application/pdf", "%PDF-1.4 fake".getBytes());
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.pdf");
        given(rawRecordRepository.save(any(RawRecord.class))).willAnswer(invocation -> invocation.getArgument(0));

        rawRecordService.ingest(ORG_USER_ID, file);

        verify(agentQueueGuard).requireRoom(ORGANIZATION_ID, 0);
        verify(journalEntryRepository, never()).save(any());
    }

    /** 업로드 처리 자리와 기다리는 줄이 꽉 차면 바이트를 읽지도, 저장하지도 않는다. */
    @Test
    void ingest_uploadGateFull_rejectsBeforeReadingAndStorage() throws Exception {
        RawRecordProperties oneSlot = new RawRecordProperties(new RawRecordProperties.Limits(500),
                new RawRecordProperties.Upload(1, 0, Duration.ofSeconds(10)));
        RawRecordUploadGate gate = new RawRecordUploadGate(oneSlot);
        RawRecordService service = newService(oneSlot, gate);
        given(userService.getOrganizationIdOf(ORG_USER_ID)).willReturn(ORGANIZATION_ID);
        MockMultipartFile file = org.mockito.Mockito.spy(datedTxt(1));

        try (RawRecordUploadGate.Slot held = gate.acquire()) {
            assertThatThrownBy(() -> service.ingest(ORG_USER_ID, file))
                    .isInstanceOf(RawRecordUploadBusyException.class);
        }

        verify(file, never()).getBytes();
        verify(rawFileStorage, never()).store(any(), anyString());
    }

    /** 파일명에 명부 아동 이름이 정확히 한 명 포함되면 표지 힌트로 채운다. */
    @Test
    void ingest_appliesHintWhenExactlyOneRosterNameInFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "0821_박서연_관찰일지.txt", "text/plain", "오늘 있었던 일".getBytes());
        given(rawFileStorage.store(any(), anyString())).willReturn("generated-uuid.txt");
        given(rawRecordRepository.save(any(RawRecord.class)))
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
        given(rawRecordRepository.save(any(RawRecord.class)))
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
        given(rawRecordRepository.save(any(RawRecord.class)))
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
        given(rawRecordRepository.save(any(RawRecord.class)))
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
        given(rawRecordRepository.save(any(RawRecord.class)))
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
