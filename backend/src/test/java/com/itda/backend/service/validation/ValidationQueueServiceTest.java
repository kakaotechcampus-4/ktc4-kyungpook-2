package com.itda.backend.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.validation.ValidationQueueItemResponse;
import com.itda.backend.exception.validation.ValidationResultErrorCode;
import com.itda.backend.exception.validation.ValidationResultNotFoundException;
import com.itda.backend.exception.validation.ValidationResultValidationException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.validation.ValidationResultRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;
import com.itda.backend.exception.agent.AgentQueueFullException;
import com.itda.backend.service.agent.AgentQueueGuard;

@ExtendWith(MockitoExtension.class)
class ValidationQueueServiceTest {

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
    @Mock
    private ChildOrganizationRepository childOrganizationRepository;
    @Mock
    private ConsentStateReader consentStateReader;
    @Mock
    private AgentQueueGuard agentQueueGuard;

    private ValidationQueueService validationQueueService;

    @BeforeEach
    void setUp() {
        validationQueueService = new ValidationQueueService(
                validationResultRepository, journalEntryRepository, rawRecordRepository,
                childRepository, userService, childOrganizationRepository, consentStateReader, agentQueueGuard);
    }

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

    @Test
    void getBlockedQueue_translatesIssueTypeIntoSentenceTeacherCanAct() {
        givenOwnedBlockedEntry(blockedResult("[\"개인정보표현\"]"), blockedEntry(), OUR_INSTITUTION);
        given(childRepository.findById(CHILD_ID))
                .willReturn(Optional.of(Child.of("김하늘", LocalDate.of(2020, 1, 1))));

        List<ValidationQueueItemResponse> queue = validationQueueService.getBlockedQueue(OUR_USER_ID);

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).childName()).isEqualTo("김하늘");
        assertThat(queue.get(0).violationReason())
                .isEqualTo("전화번호·주민번호 같은 개인정보가 그대로 적혀 있어요");
        assertThat(queue.get(0).violationCode()).containsExactly("개인정보표현");
    }

    @Test
    void getBlockedQueue_joinsAllViolationReasons() {
        givenOwnedBlockedEntry(blockedResult("[\"진단명\",\"다수아동언급\"]"), blockedEntry(), OUR_INSTITUTION);
        given(childRepository.findById(CHILD_ID)).willReturn(Optional.empty());

        List<ValidationQueueItemResponse> queue = validationQueueService.getBlockedQueue(OUR_USER_ID);

        assertThat(queue.get(0).violationReason())
                .isEqualTo("진단명이나 장애 유형이 단정적으로 적혀 있어요 / 다른 아이의 이름이 함께 적혀 있어요");
    }

    @Test
    void getBlockedQueue_describesHealthRecordReason() {
        givenOwnedBlockedEntry(blockedResult("[\"진단명\",\"건강관리기록\"]"), blockedEntry(), OUR_INSTITUTION);
        given(childRepository.findById(CHILD_ID)).willReturn(Optional.empty());

        List<ValidationQueueItemResponse> queue = validationQueueService.getBlockedQueue(OUR_USER_ID);

        assertThat(queue.get(0).violationReason())
                .isEqualTo("진단명이나 장애 유형이 단정적으로 적혀 있어요 / 질병·복약·병원 진료 같은 건강 정보가 적혀 있어요");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{깨진 json", "[]"})
    void getBlockedQueue_fallsBackToGenericReasonWhenIssueTypesUnusable(String issueTypes) {
        givenOwnedBlockedEntry(blockedResult(issueTypes), blockedEntry(), OUR_INSTITUTION);
        given(childRepository.findById(CHILD_ID)).willReturn(Optional.empty());

        List<ValidationQueueItemResponse> queue = validationQueueService.getBlockedQueue(OUR_USER_ID);

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).violationReason()).isEqualTo("검증에서 문제가 발견됐어요. 원본을 확인해 주세요");
    }

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

    @Test
    void getBlockedQueue_excludesAlreadyResolvedEntries() {
        JournalEntry resolved = blockedEntry();
        resolved.holdAfterValidation();
        givenOwnedBlockedEntry(blockedResult("[\"진단명\"]"), resolved, OUR_INSTITUTION);

        assertThat(validationQueueService.getBlockedQueue(OUR_USER_ID)).isEmpty();
    }

    @Test
    void getBlockedQueue_showsOnlyLatestResultWhenEntryWasValidatedTwice() {
        ValidationResult older = ValidationResult.of(
                1L, 9L, CHILD_ID, ValidationVerdict.BLOCK, "[\"진단명\"]", null, null);
        ReflectionTestUtils.setField(older, "id", 10L);
        ValidationResult latest = blockedResult("[\"개인정보표현\"]");

        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
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

    @Test
    void resolveReupload_isNoLongerSupported() {
        JournalEntry entry = blockedEntry();

        assertThatThrownBy(() -> validationQueueService.resolve(11L, "reupload", OUR_USER_ID))
                .isInstanceOf(ValidationResultValidationException.class);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_BLOCKED);
    }

    @Test
    void resolveHold_endsPipelineForThisEntry() {
        JournalEntry entry = blockedEntry();
        givenOwnedResult(blockedResult("[\"진단명\"]"), entry, OUR_INSTITUTION);

        validationQueueService.resolve(11L, "hold", OUR_USER_ID);

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_HELD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "approve", "REUPLOAD"})
    void resolve_unsupportedAction_throwsValidationException(String action) {
        assertThatThrownBy(() -> validationQueueService.resolve(11L, action, OUR_USER_ID))
                .isInstanceOf(ValidationResultValidationException.class);
        verifyNoInteractions(journalEntryRepository);
    }

    @Test
    void resolve_alreadyResolvedEntry_throwsValidationException() {
        JournalEntry resolved = blockedEntry();
        resolved.holdAfterValidation();
        givenOwnedResult(blockedResult("[\"진단명\"]"), resolved, OUR_INSTITUTION);

        assertRejectedWith(() -> validationQueueService.resolve(11L, "hold", OUR_USER_ID),
                ValidationResultErrorCode.INVALID_REQUEST);
    }

    @Test
    void resolve_nonBlockedResult_throwsValidationException() {
        ValidationResult passed = ValidationResult.of(1L, 9L, CHILD_ID, ValidationVerdict.PASS, null, null, null);
        ReflectionTestUtils.setField(passed, "id", 11L);
        givenOwnedResult(passed, blockedEntry(), OUR_INSTITUTION);

        assertRejectedWith(() -> validationQueueService.resolve(11L, "hold", OUR_USER_ID),
                ValidationResultErrorCode.INVALID_REQUEST);
        verify(journalEntryRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void resolve_otherInstitutionsRecord_throwsNotFoundWithoutLocking() {
        givenOwnedResult(blockedResult("[\"진단명\"]"), blockedEntry(), OTHER_INSTITUTION);

        assertThatThrownBy(() -> validationQueueService.resolve(11L, "hold", OUR_USER_ID))
                .isInstanceOf(ValidationResultNotFoundException.class);
        verify(journalEntryRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void resolve_unknownId_throwsNotFound() {
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(validationResultRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> validationQueueService.resolve(99L, "hold", OUR_USER_ID))
                .isInstanceOf(ValidationResultNotFoundException.class);
    }

    @Test
    void resolve_entryDeletedBeforeLock_throwsNotFound() {
        givenOwnedResult(blockedResult("[\"진단명\"]"), blockedEntry(), OUR_INSTITUTION);
        given(journalEntryRepository.findByIdForUpdate(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> validationQueueService.resolve(11L, "hold", OUR_USER_ID))
                .isInstanceOf(ValidationResultNotFoundException.class);
    }

    @Test
    void resolve_staleResult_throwsOutdated() {
        JournalEntry entry = blockedEntry();
        givenOwnedResult(blockedResult("[\"진단명\"]"), entry, OUR_INSTITUTION);
        givenNewerResult();

        assertRejectedWith(() -> validationQueueService.resolve(11L, "hold", OUR_USER_ID),
                ValidationResultErrorCode.OUTDATED);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_BLOCKED);
    }

    @Test
    void revise_replacesContentAndSendsEntryBackToValidation() {
        JournalEntry entry = blockedEntry();
        givenRevisable(blockedResult("[\"개인정보표현\"]"), entry);

        validationQueueService.revise(11L, "점심시간에 식사를 잘함. 연락처는 지움", OUR_USER_ID);

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
        assertThat(entry.getContent()).isEqualTo("점심시간에 식사를 잘함. 연락처는 지움");
        assertThat(entry.getChildId()).isEqualTo(CHILD_ID);
        assertThat(entry.getRevisedBy()).isEqualTo(OUR_USER_ID);
    }

    @Test
    void revise_checksOwnershipThenLocksChildOnceBeforeEntry() {
        givenRevisable(blockedResult("[\"진단명\"]"), blockedEntry());

        validationQueueService.revise(11L, "고친 글", OUR_USER_ID);

        InOrder order = inOrder(journalEntryRepository, childRepository);
        order.verify(journalEntryRepository).existsOwnedBy(1L, OUR_INSTITUTION);
        order.verify(childRepository).findByIdForUpdate(CHILD_ID);
        order.verify(journalEntryRepository).findByIdForUpdate(1L);
        verify(childRepository).findByIdForUpdate(CHILD_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"점심시간에 식사를 잘함", " 점심시간에 식사를 잘함\n"})
    void revise_unchangedContent_throwsContentUnchanged(String content) {
        JournalEntry entry = blockedEntry();
        givenRevisable(blockedResult("[\"진단명\"]"), entry);

        assertRejectedWith(() -> validationQueueService.revise(11L, content, OUR_USER_ID),
                ValidationResultErrorCode.CONTENT_UNCHANGED);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_BLOCKED);
    }

    @Test
    void revise_blankContent_throwsInvalidRequest() {
        JournalEntry entry = blockedEntry();
        givenRevisable(blockedResult("[\"진단명\"]"), entry);

        assertRejectedWith(() -> validationQueueService.revise(11L, "   ", OUR_USER_ID),
                ValidationResultErrorCode.INVALID_REQUEST);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_BLOCKED);
    }

    @Test
    void revise_contentLongerThanMax_throwsInvalidRequest() {
        JournalEntry entry = blockedEntry();
        givenRevisable(blockedResult("[\"진단명\"]"), entry);

        assertRejectedWith(() -> validationQueueService.revise(
                        11L, "가".repeat(JournalEntry.MAX_CONTENT_LENGTH + 1), OUR_USER_ID),
                ValidationResultErrorCode.INVALID_REQUEST);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_BLOCKED);
    }

    @Test
    void revise_agentQueueFull_leavesEntryBlocked() {
        JournalEntry entry = blockedEntry();
        givenRevisable(blockedResult("[\"진단명\"]"), entry);
        org.mockito.BDDMockito.willThrow(new AgentQueueFullException(OUR_ORG_ID, 1000, 1, 1000))
                .given(agentQueueGuard).requireRoom(OUR_ORG_ID, 1);

        assertThatThrownBy(() -> validationQueueService.revise(11L, "고친 글", OUR_USER_ID))
                .isInstanceOf(AgentQueueFullException.class);

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_BLOCKED);
        assertThat(entry.getContent()).isEqualTo("점심시간에 식사를 잘함");
        InOrder order = inOrder(childRepository, journalEntryRepository, agentQueueGuard);
        order.verify(childRepository).findByIdForUpdate(CHILD_ID);
        order.verify(journalEntryRepository).findByIdForUpdate(1L);
        order.verify(agentQueueGuard).requireRoom(OUR_ORG_ID, 1);
    }

    @Test
    void revise_alreadyResolvedEntry_throwsValidationException() {
        JournalEntry held = blockedEntry();
        held.holdAfterValidation();
        givenRevisable(blockedResult("[\"진단명\"]"), held);

        assertRejectedWith(() -> validationQueueService.revise(11L, "고친 글", OUR_USER_ID),
                ValidationResultErrorCode.INVALID_REQUEST);
    }

    @Test
    void revise_nonBlockedResult_throwsValidationExceptionWithoutLocking() {
        ValidationResult passed = ValidationResult.of(1L, 9L, CHILD_ID, ValidationVerdict.PASS, null, null, null);
        ReflectionTestUtils.setField(passed, "id", 11L);
        givenRevisable(passed, blockedEntry());

        assertRejectedWith(() -> validationQueueService.revise(11L, "고친 글", OUR_USER_ID),
                ValidationResultErrorCode.INVALID_REQUEST);
        verify(childRepository, never()).findByIdForUpdate(any());
        verify(journalEntryRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void revise_otherInstitutionsRecord_throwsNotFoundWithoutLocking() {
        givenOwnedResult(blockedResult("[\"진단명\"]"), blockedEntry(), OTHER_INSTITUTION);

        assertThatThrownBy(() -> validationQueueService.revise(11L, "고친 글", OUR_USER_ID))
                .isInstanceOf(ValidationResultNotFoundException.class);
        verifyNoInteractions(childRepository);
        verify(journalEntryRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void revise_unknownId_throwsNotFound() {
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(validationResultRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> validationQueueService.revise(99L, "고친 글", OUR_USER_ID))
                .isInstanceOf(ValidationResultNotFoundException.class);
    }

    @Test
    void revise_staleResultAfterAlreadyRevised_throwsOutdated() {
        JournalEntry entry = blockedEntry();
        givenRevisable(blockedResult("[\"진단명\"]"), entry);
        givenNewerResult();

        assertRejectedWith(() -> validationQueueService.revise(11L, "고친 글", OUR_USER_ID),
                ValidationResultErrorCode.OUTDATED);
        assertThat(entry.getContent()).isEqualTo("점심시간에 식사를 잘함");
    }

    @Test
    void revise_consentNoLongerGranted_throwsChildNotShared() {
        JournalEntry entry = blockedEntry();
        givenRevisable(blockedResult("[\"진단명\"]"), entry);
        given(consentStateReader.stateOf(CHILD_ID, OUR_ORG_ID)).willReturn(ConsentState.REVOKED);

        assertRejectedWith(() -> validationQueueService.revise(11L, "고친 글", OUR_USER_ID),
                ValidationResultErrorCode.CHILD_NOT_SHARED);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_BLOCKED);
    }

    @Test
    void revise_childRemovedFromRoster_throwsChildNotShared() {
        JournalEntry entry = blockedEntry();
        givenRevisable(blockedResult("[\"진단명\"]"), entry);
        given(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(CHILD_ID, OUR_ORG_ID))
                .willReturn(false);

        assertRejectedWith(() -> validationQueueService.revise(11L, "고친 글", OUR_USER_ID),
                ValidationResultErrorCode.CHILD_NOT_SHARED);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_BLOCKED);
    }

    @Test
    void revise_deletedChild_throwsChildNotShared() {
        JournalEntry entry = blockedEntry();
        givenRevisable(blockedResult("[\"진단명\"]"), entry);
        Child deleted = Child.of("김하늘", LocalDate.of(2020, 1, 1));
        deleted.delete();
        given(childRepository.findByIdForUpdate(CHILD_ID)).willReturn(Optional.of(deleted));

        assertRejectedWith(() -> validationQueueService.revise(11L, "고친 글", OUR_USER_ID),
                ValidationResultErrorCode.CHILD_NOT_SHARED);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_BLOCKED);
    }

    @Test
    void revise_blockedResultWithoutChild_isNotReportedAsBadInput() {
        ValidationResult result = ValidationResult.of(1L, 9L, null, ValidationVerdict.BLOCK, "[\"진단명\"]", null, null);
        ReflectionTestUtils.setField(result, "id", 11L);
        givenRevisable(result, blockedEntry());

        assertThatThrownBy(() -> validationQueueService.revise(11L, "고친 글", OUR_USER_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(journalEntryRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void revise_entryChildDiffersFromResult_isNotReportedAsBadInput() {
        JournalEntry entry = blockedEntry();
        ReflectionTestUtils.setField(entry, "childId", 6L);
        givenRevisable(blockedResult("[\"진단명\"]"), entry);

        assertThatThrownBy(() -> validationQueueService.revise(11L, "고친 글", OUR_USER_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.VALIDATION_BLOCKED);
    }

    private static void assertRejectedWith(ThrowingCallable call, ValidationResultErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ValidationResultValidationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private void givenOwnedResult(ValidationResult result, JournalEntry entry, String institutionId) {
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(validationResultRepository.findById(11L)).willReturn(Optional.of(result));
        given(journalEntryRepository.existsOwnedBy(1L, OUR_INSTITUTION))
                .willReturn(OUR_INSTITUTION.equals(institutionId));
        lenient().when(journalEntryRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(entry));
        lenient().when(validationResultRepository.findFirstByJournalEntryIdOrderByIdDesc(1L))
                .thenReturn(Optional.of(result));
    }

    private void givenNewerResult() {
        ValidationResult newer = blockedResult("[\"진단명\"]");
        ReflectionTestUtils.setField(newer, "id", 20L);
        given(validationResultRepository.findFirstByJournalEntryIdOrderByIdDesc(1L)).willReturn(Optional.of(newer));
    }

    private void givenRevisable(ValidationResult result, JournalEntry entry) {
        givenOwnedResult(result, entry, OUR_INSTITUTION);
        lenient().when(childRepository.findByIdForUpdate(CHILD_ID))
                .thenReturn(Optional.of(Child.of("김하늘", LocalDate.of(2020, 1, 1))));
        lenient().when(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(
                CHILD_ID, OUR_ORG_ID)).thenReturn(true);
        lenient().when(consentStateReader.stateOf(CHILD_ID, OUR_ORG_ID)).thenReturn(ConsentState.GRANTED);
    }
}
