package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.domain.ValidationResult;
import com.itda.backend.domain.ValidationVerdict;
import com.itda.backend.dto.ValidationQueueItemResponse;
import com.itda.backend.exception.ValidationResultNotFoundException;
import com.itda.backend.exception.ValidationResultValidationException;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.RawRecordRepository;
import com.itda.backend.repository.ValidationResultRepository;

@ExtendWith(MockitoExtension.class)
class ValidationQueueServiceTest {

    // principal은 userId이고, RawRecord.institutionId와 비교되는 값은 getOrganizationIdOf로 풀린다
    // — MatchingResultServiceTest와 같은 계약이다.
    private static final String OUR_USER_ID = "u-1";
    private static final Long OUR_ORG_ID = 1L;
    private static final String OUR_INSTITUTION = String.valueOf(OUR_ORG_ID);
    private static final String OTHER_INSTITUTION = "2";
    private static final Long CHILD_ID = 5L;

    @Mock
    private ValidationResultRepository validationResultRepository;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private RawRecordRepository rawRecordRepository;
    @Mock
    private ChildRepository childRepository;
    @Mock
    private UserService userService;

    private ValidationQueueService validationQueueService;

    @BeforeEach
    void setUp() {
        validationQueueService = new ValidationQueueService(
                validationResultRepository, journalEntryRepository, rawRecordRepository,
                childRepository, userService);
    }

    /** 검증에서 BLOCK 으로 막힌 일지 — 수정 요청 큐에 뜨는 상태다. */
    private JournalEntry blockedEntry() {
        JournalEntry entry = JournalEntry.of(3L, LocalDate.of(2026, 9, 1), "점심시간에 식사를 잘함", 1);
        entry.startMatching();
        entry.confirmMatch(CHILD_ID);
        entry.startValidating();
        entry.blockValidation();
        return entry;
    }

    private RawRecord rawRecordOf(String institutionId) {
        return new RawRecord(institutionId, "0821_특이사항.txt", "stored.txt", "text/plain", 10L,
                RawRecordStatus.PENDING);
    }

    private ValidationResult blockedResult(String issueTypesJson) {
        ValidationResult result = ValidationResult.of(
                1L, 9L, CHILD_ID, ValidationVerdict.BLOCK, issueTypesJson, null, null);
        ReflectionTestUtils.setField(result, "id", 11L);
        return result;
    }

    private void givenOwnedBlockedEntry(ValidationResult result, JournalEntry entry, String institutionId) {
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(validationResultRepository.findByVerdictOrderByIdDesc(ValidationVerdict.BLOCK))
                .willReturn(List.of(result));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L))
                .willReturn(Optional.of(rawRecordOf(institutionId)));
    }

    // --- 조회 (O-24) -------------------------------------------------------------

    @Test
    void getBlockedQueue_translatesIssueTypeIntoSentenceTeacherCanAct() {
        givenOwnedBlockedEntry(blockedResult("[\"개인정보표현\"]"), blockedEntry(), OUR_INSTITUTION);
        given(childRepository.findById(CHILD_ID))
                .willReturn(Optional.of(Child.of("김하늘", LocalDate.of(2020, 1, 1))));

        List<ValidationQueueItemResponse> queue = validationQueueService.getBlockedQueue(OUR_USER_ID);

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).childName()).isEqualTo("김하늘");
        assertThat(queue.get(0).violationReason())
                .isEqualTo("전화번호·주민번호 같은 개인정보가 그대로 적혀 있습니다");
        // 화면이 코드로 분기할 수 있게 원본도 같이 내려준다.
        assertThat(queue.get(0).violationCode()).containsExactly("개인정보표현");
    }

    /** 유형이 여러 개면 이유도 모두 보여준다 — 하나만 고치고 다시 올리면 또 막힌다. */
    @Test
    void getBlockedQueue_joinsAllViolationReasons() {
        givenOwnedBlockedEntry(blockedResult("[\"진단명\",\"다수아동언급\"]"), blockedEntry(), OUR_INSTITUTION);
        given(childRepository.findById(CHILD_ID)).willReturn(Optional.empty());

        List<ValidationQueueItemResponse> queue = validationQueueService.getBlockedQueue(OUR_USER_ID);

        assertThat(queue.get(0).violationReason())
                .isEqualTo("진단명이나 장애 유형이 단정적으로 적혀 있습니다 / 다른 아이의 이름이 함께 적혀 있습니다");
    }

    /** AI가 유형을 안 보내거나 깨진 JSON을 보내도 큐 조회 전체가 죽지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"", "{깨진 json", "[]"})
    void getBlockedQueue_fallsBackToGenericReasonWhenIssueTypesUnusable(String issueTypes) {
        givenOwnedBlockedEntry(blockedResult(issueTypes), blockedEntry(), OUR_INSTITUTION);
        given(childRepository.findById(CHILD_ID)).willReturn(Optional.empty());

        List<ValidationQueueItemResponse> queue = validationQueueService.getBlockedQueue(OUR_USER_ID);

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).violationReason()).isEqualTo("검증에서 문제가 발견됐습니다. 원본을 확인해주세요");
    }

    /** AI가 모르는 유형을 새로 만들어 보내면 코드라도 보여준다 — 빈칸이면 선생님이 할 수 있는 게 없다. */
    @Test
    void getBlockedQueue_showsRawCodeForUnknownIssueType() {
        givenOwnedBlockedEntry(blockedResult("[\"새로운유형\"]"), blockedEntry(), OUR_INSTITUTION);
        given(childRepository.findById(CHILD_ID)).willReturn(Optional.empty());

        assertThat(validationQueueService.getBlockedQueue(OUR_USER_ID).get(0).violationReason())
                .isEqualTo("새로운유형");
    }

    @Test
    void getBlockedQueue_excludesOtherInstitutionsRecords() {
        givenOwnedBlockedEntry(blockedResult("[\"진단명\"]"), blockedEntry(), OTHER_INSTITUTION);

        assertThat(validationQueueService.getBlockedQueue(OUR_USER_ID)).isEmpty();
    }

    /** 이미 처리된 일지는 눌러도 resolve가 거절하므로 큐에도 띄우지 않는다. */
    @Test
    void getBlockedQueue_excludesAlreadyResolvedEntries() {
        JournalEntry resolved = blockedEntry();
        resolved.holdAfterValidation();
        givenOwnedBlockedEntry(blockedResult("[\"진단명\"]"), resolved, OUR_INSTITUTION);

        assertThat(validationQueueService.getBlockedQueue(OUR_USER_ID)).isEmpty();
    }

    /**
     * 한 일지를 다시 검증하면 BLOCK 행이 쌓인다(§8.1 — 결과는 수정하지 않고 새로 쌓는다).
     * 같은 기록이 큐에 두 번 뜨면 선생님이 하나만 처리하고 나머지가 남으므로 최신 판정만 남긴다.
     */
    @Test
    void getBlockedQueue_showsOnlyLatestResultWhenEntryWasValidatedTwice() {
        ValidationResult older = ValidationResult.of(
                1L, 9L, CHILD_ID, ValidationVerdict.BLOCK, "[\"진단명\"]", null, null);
        ReflectionTestUtils.setField(older, "id", 10L);
        ValidationResult latest = blockedResult("[\"개인정보표현\"]"); // id = 11

        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        // 리포지토리는 id 내림차순으로 준다 — 최신이 먼저다.
        given(validationResultRepository.findByVerdictOrderByIdDesc(ValidationVerdict.BLOCK))
                .willReturn(List.of(latest, older));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(blockedEntry()));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L))
                .willReturn(Optional.of(rawRecordOf(OUR_INSTITUTION)));
        given(childRepository.findById(CHILD_ID)).willReturn(Optional.empty());

        List<ValidationQueueItemResponse> queue = validationQueueService.getBlockedQueue(OUR_USER_ID);

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).id()).isEqualTo("11");
        assertThat(queue.get(0).violationCode()).containsExactly("개인정보표현");
    }

    /**
     * 위반 문장이 60자 뒤에 있으면 미리보기만으로는 무엇을 고쳐야 할지 알 수 없다 —
     * 매칭 확인 큐와 같은 이유로 전체 본문도 함께 내려준다(#111).
     */
    @Test
    void getBlockedQueue_returnsFullContentNotJustPreview() {
        String longContent = "오늘 활동 기록을 길게 적어 둔 문장입니다. ".repeat(5) + "끝부분에 있는 전화번호 010-1234-5678";
        JournalEntry entry = JournalEntry.of(3L, LocalDate.of(2026, 9, 1), longContent, 1);
        entry.startMatching();
        entry.confirmMatch(CHILD_ID);
        entry.startValidating();
        entry.blockValidation();
        givenOwnedBlockedEntry(blockedResult("[\"개인정보표현\"]"), entry, OUR_INSTITUTION);
        given(childRepository.findById(CHILD_ID)).willReturn(Optional.empty());

        var record = validationQueueService.getBlockedQueue(OUR_USER_ID).get(0).record();

        assertThat(longContent.length()).isGreaterThan(60);
        assertThat(record.preview()).isEqualTo(longContent.substring(0, 60) + "…");
        assertThat(record.fullContent()).isEqualTo(longContent);
    }

    // --- 처리 (O-25) -------------------------------------------------------------

    @Test
    void resolveReupload_endsPipelineForThisEntry() {
        JournalEntry entry = blockedEntry();
        givenOwnedResultForResolve(blockedResult("[\"진단명\"]"), entry, OUR_INSTITUTION);

        validationQueueService.resolve(11L, "reupload", OUR_USER_ID);

        // 원본은 고치지도 지우지도 않는다(append-only) — 이 일지만 여기서 끝난다.
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.REUPLOAD_REQUESTED);
    }

    @Test
    void resolveHold_endsPipelineForThisEntry() {
        JournalEntry entry = blockedEntry();
        givenOwnedResultForResolve(blockedResult("[\"진단명\"]"), entry, OUR_INSTITUTION);

        validationQueueService.resolve(11L, "hold", OUR_USER_ID);

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_HELD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "approve", "REUPLOAD"})
    void resolve_unsupportedAction_throwsValidationException(String action) {
        givenOwnedResultForResolve(blockedResult("[\"진단명\"]"), blockedEntry(), OUR_INSTITUTION);

        assertThatThrownBy(() -> validationQueueService.resolve(11L, action, OUR_USER_ID))
                .isInstanceOf(ValidationResultValidationException.class);
    }

    /** 다른 선생님이 먼저 처리했으면 거절한다 — 큐에서 사라지기 전에 눌렀을 수 있다. */
    @Test
    void resolve_alreadyResolvedEntry_throwsValidationException() {
        JournalEntry resolved = blockedEntry();
        resolved.requestReupload();
        givenOwnedResultForResolve(blockedResult("[\"진단명\"]"), resolved, OUR_INSTITUTION);

        assertThatThrownBy(() -> validationQueueService.resolve(11L, "hold", OUR_USER_ID))
                .isInstanceOf(ValidationResultValidationException.class);
    }

    /** BLOCK이 아닌 결과(PASS·REVIEW·FAILED)로는 이 API를 쓸 수 없다. */
    @Test
    void resolve_nonBlockedResult_throwsValidationException() {
        ValidationResult passed = ValidationResult.of(1L, 9L, CHILD_ID, ValidationVerdict.PASS, null, null, null);
        ReflectionTestUtils.setField(passed, "id", 11L);
        givenOwnedResultForResolve(passed, blockedEntry(), OUR_INSTITUTION);

        assertThatThrownBy(() -> validationQueueService.resolve(11L, "hold", OUR_USER_ID))
                .isInstanceOf(ValidationResultValidationException.class);
    }

    /** 다른 기관 기록은 "권한 없음"이 아니라 "없음"으로 응답한다. */
    @Test
    void resolve_otherInstitutionsRecord_throwsNotFound() {
        givenOwnedResultForResolve(blockedResult("[\"진단명\"]"), blockedEntry(), OTHER_INSTITUTION);

        assertThatThrownBy(() -> validationQueueService.resolve(11L, "hold", OUR_USER_ID))
                .isInstanceOf(ValidationResultNotFoundException.class);
    }

    @Test
    void resolve_unknownId_throwsNotFound() {
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(validationResultRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> validationQueueService.resolve(99L, "hold", OUR_USER_ID))
                .isInstanceOf(ValidationResultNotFoundException.class);
    }

    private void givenOwnedResultForResolve(ValidationResult result, JournalEntry entry, String institutionId) {
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(validationResultRepository.findById(11L)).willReturn(Optional.of(result));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L))
                .willReturn(Optional.of(rawRecordOf(institutionId)));
    }
}
