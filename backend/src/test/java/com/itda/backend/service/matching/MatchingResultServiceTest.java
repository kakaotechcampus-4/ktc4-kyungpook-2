package com.itda.backend.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import java.math.BigDecimal;
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

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.consent.SharingConsentStatus;
import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.matching.MatchingStatus;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.dto.response.matching.MatchingQueueItemResponse;
import com.itda.backend.exception.matching.MatchingResultNotFoundException;
import com.itda.backend.exception.matching.MatchingResultValidationException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.matching.MatchingResultRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.service.user.UserService;
import com.itda.backend.exception.agent.AgentQueueFullException;
import com.itda.backend.service.agent.AgentQueueGuard;

@ExtendWith(MockitoExtension.class)
class MatchingResultServiceTest {

    private static final String OUR_USER_ID = "u-1";
    private static final Long OUR_ORG_ID = 1L;
    private static final Long OTHER_ORG_ID = 2L;
    private static final String OUR_INSTITUTION = String.valueOf(OUR_ORG_ID);
    private static final String OTHER_INSTITUTION = String.valueOf(OTHER_ORG_ID);

    @Mock
    private MatchingResultRepository matchingResultRepository;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private RawRecordRepository rawRecordRepository;
    @Mock
    private ChildRepository childRepository;
    @Mock
    private ChildOrganizationRepository childOrganizationRepository;
    @Mock
    private SharingConsentRepository sharingConsentRepository;
    @Mock
    private UserService userService;
    @Mock
    private AgentQueueGuard agentQueueGuard;

    private MatchingResultService matchingResultService;

    @BeforeEach
    void setUp() {
        matchingResultService = new MatchingResultService(
                matchingResultRepository, journalEntryRepository, rawRecordRepository,
                childRepository, childOrganizationRepository, sharingConsentRepository, userService, agentQueueGuard);
    }

    private JournalEntry ourJournalEntry() {
        JournalEntry entry = JournalEntry.of(3L, LocalDate.of(2026, 9, 1), "점심시간에 식사를 잘함", 1);
        entry.startMatching();
        entry.requestMatchReview();
        return entry;
    }

    private Child child() {
        return Child.of("김하늘", LocalDate.of(2020, 1, 1));
    }

    private void consentedToOurOrganization(boolean consented) {
        given(sharingConsentRepository.existsByChildIdAndOrganizationIdAndStatus(2L, OUR_ORG_ID, SharingConsentStatus.ACTIVE))
                .willReturn(consented);
    }

    private RawRecord ourRawRecord(String institutionId) {
        return new RawRecord(institutionId, "0821_관찰일지.docx", "stored.docx", "text/plain", 10L, RawRecordStatus.PENDING);
    }

    @Test
    void getQueue_includesOnlyOwnInstitutionsEntries() {
        MatchingResult ours = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findByStatusNot(MatchingStatus.AUTO)).willReturn(List.of(ours));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));

        List<MatchingQueueItemResponse> queue = matchingResultService.getQueue(OUR_USER_ID);

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).record().fileName()).isEqualTo("0821_관찰일지.docx");
    }

    @Test
    void getQueue_returnsFilenameHintNameOrNull() {
        MatchingResult hinted = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        MatchingResult plain = new MatchingResult(
                2L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        RawRecord hintedRecord = ourRawRecord(OUR_INSTITUTION);
        hintedRecord.applyHint("김하늘", LocalDate.of(2020, 1, 1));
        JournalEntry plainEntry = JournalEntry.of(4L, LocalDate.of(2026, 9, 1), "점심시간에 식사를 잘함", 1);
        plainEntry.startMatching();
        plainEntry.requestMatchReview();
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findByStatusNot(MatchingStatus.AUTO)).willReturn(List.of(hinted, plain));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(plainEntry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(hintedRecord));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(4L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));

        List<MatchingQueueItemResponse> queue = matchingResultService.getQueue(OUR_USER_ID);

        assertThat(queue).extracting(MatchingQueueItemResponse::hintName).containsExactly("김하늘", null);
    }

    @Test
    void getQueue_keepsShortPreviewButAlsoReturnsFullContent() {
        String longContent = "점심시간에 식사를 잘 마쳤고 ".repeat(10) + "뒷부분에 등장하는 중요한 이름: 김하늘";
        JournalEntry entry = JournalEntry.of(3L, LocalDate.of(2026, 9, 1), longContent, 1);
        entry.startMatching();
        entry.requestMatchReview();
        MatchingResult ours = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findByStatusNot(MatchingStatus.AUTO)).willReturn(List.of(ours));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));

        List<MatchingQueueItemResponse> queue = matchingResultService.getQueue(OUR_USER_ID);

        assertThat(longContent.length()).isGreaterThan(60);
        assertThat(queue.get(0).record().preview()).isEqualTo(longContent.substring(0, 60) + "…");
        assertThat(queue.get(0).record().fullContent()).isEqualTo(longContent);
    }

    @Test
    void getQueue_excludesEntriesNotAwaitingReview() {
        MatchingResult stale = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        JournalEntry resolved = ourJournalEntry();
        resolved.confirmMatchByReviewer(5L);
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findByStatusNot(MatchingStatus.AUTO)).willReturn(List.of(stale));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(resolved));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));

        assertThat(matchingResultService.getQueue(OUR_USER_ID)).isEmpty();
    }

    @Test
    void getQueue_skipsMalformedCandidateInsteadOfThrowing() {
        MatchingResult ours = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.MULTI, null, null,
                "[{\"confidence\":0.8}]", null, null, null, "v1");
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findByStatusNot(MatchingStatus.AUTO)).willReturn(List.of(ours));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));

        List<MatchingQueueItemResponse> queue = matchingResultService.getQueue(OUR_USER_ID);

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).candidates()).isEmpty();
    }

    @Test
    void getQueue_excludesOtherInstitutionsEntries() {
        MatchingResult theirs = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findByStatusNot(MatchingStatus.AUTO)).willReturn(List.of(theirs));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OTHER_INSTITUTION)));

        List<MatchingQueueItemResponse> queue = matchingResultService.getQueue(OUR_USER_ID);

        assertThat(queue).isEmpty();
    }

    @Test
    void getItem_returnsOwnPendingItem() {
        MatchingResult ours = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(7L)).willReturn(Optional.of(ours));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));

        MatchingQueueItemResponse item = matchingResultService.getItem(7L, OUR_USER_ID);

        assertThat(item.record().fullContent()).isEqualTo("점심시간에 식사를 잘함");
    }

    @Test
    void getItem_otherInstitutionsEntry_throwsNotFound() {
        MatchingResult theirs = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(7L)).willReturn(Optional.of(theirs));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OTHER_INSTITUTION)));

        assertThatThrownBy(() -> matchingResultService.getItem(7L, OUR_USER_ID))
                .isInstanceOf(MatchingResultNotFoundException.class);
    }

    @Test
    void getItem_entryNotAwaitingReview_throwsNotFound() {
        MatchingResult stale = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        JournalEntry resolved = ourJournalEntry();
        resolved.confirmMatchByReviewer(5L);
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(7L)).willReturn(Optional.of(stale));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(resolved));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));

        assertThatThrownBy(() -> matchingResultService.getItem(7L, OUR_USER_ID))
                .isInstanceOf(MatchingResultNotFoundException.class);
    }

    @Test
    void getItem_autoResult_throwsNotFound() {
        MatchingResult auto = new MatchingResult(
                1L, 2L, new BigDecimal("0.9"), MatchingStatus.AUTO, null, null, null, null, null, null, "v1");
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(7L)).willReturn(Optional.of(auto));

        assertThatThrownBy(() -> matchingResultService.getItem(7L, OUR_USER_ID))
                .isInstanceOf(MatchingResultNotFoundException.class);
    }

    @Test
    void resolveAssign_setsMatchedChildAndAutoStatus() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        JournalEntry entry = ourJournalEntry();
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(childRepository.findByIdForUpdate(2L)).willReturn(Optional.of(child()));
        given(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(2L, OUR_ORG_ID))
                .willReturn(true);
        consentedToOurOrganization(true);
        given(matchingResultRepository.save(any(MatchingResult.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MatchingQueueItemResponse resolved =
                matchingResultService.resolve(1L, "assign", 2L, "kakao-teacher-1", OUR_USER_ID);

        assertThat(resolved.matchedChildId()).isEqualTo("2");
        assertThat(resolved.status()).isEqualTo(MatchingStatus.AUTO);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
        assertThat(entry.getChildId()).isEqualTo(2L);
    }

    @Test
    void resolveAssign_agentQueueFull_leavesEntryAwaitingReview() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        JournalEntry entry = ourJournalEntry();
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(childRepository.findByIdForUpdate(2L)).willReturn(Optional.of(child()));
        given(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(2L, OUR_ORG_ID))
                .willReturn(true);
        consentedToOurOrganization(true);
        org.mockito.BDDMockito.willThrow(new AgentQueueFullException(OUR_ORG_ID, 1000, 1, 1000))
                .given(agentQueueGuard).requireRoom(OUR_ORG_ID, 1);

        assertThatThrownBy(() -> matchingResultService.resolve(1L, "assign", 2L, "kakao-teacher-1", OUR_USER_ID))
                .isInstanceOf(AgentQueueFullException.class);

        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.MATCH_REVIEW);
        assertThat(matchingResult.getStatus()).isEqualTo(MatchingStatus.REVIEW);
    }

    @Test
    void resolveNotOurs_doesNotAskForRoom() {
        MatchingResult matchingResult = new MatchingResult(
                1L, 5L, new BigDecimal("0.3"), MatchingStatus.MULTI, null, null, null, null, null, null, "v1");
        JournalEntry entry = ourJournalEntry();
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(matchingResultRepository.save(any(MatchingResult.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        matchingResultService.resolve(1L, "not_ours", null, "kakao-teacher-1", OUR_USER_ID);

        org.mockito.Mockito.verifyNoInteractions(agentQueueGuard);
    }

    @Test
    void resolveAssign_pendingConsentChild_throwsValidationException() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.UNMATCHED, null, null, null, null, null, null, "v1");
        JournalEntry entry = ourJournalEntry();
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(childRepository.findByIdForUpdate(2L)).willReturn(Optional.of(child()));
        given(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(2L, OUR_ORG_ID))
                .willReturn(true);
        consentedToOurOrganization(false);

        assertThatThrownBy(() ->
                matchingResultService.resolve(1L, "assign", 2L, "kakao-teacher-1", OUR_USER_ID))
                .isInstanceOf(MatchingResultValidationException.class);
        assertThat(matchingResult.getStatus()).isEqualTo(MatchingStatus.UNMATCHED);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.MATCH_REVIEW);
    }

    @Test
    void resolveAssign_childConsentedOnlyToOtherOrganization_throwsValidationException() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.UNMATCHED, null, null, null, null, null, null, "v1");
        JournalEntry entry = ourJournalEntry();
        Child consentedElsewhere = child();
        consentedElsewhere.activate();
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(childRepository.findByIdForUpdate(2L)).willReturn(Optional.of(consentedElsewhere));
        given(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(2L, OUR_ORG_ID))
                .willReturn(true);
        consentedToOurOrganization(false);

        assertThatThrownBy(() ->
                matchingResultService.resolve(1L, "assign", 2L, "kakao-teacher-1", OUR_USER_ID))
                .isInstanceOf(MatchingResultValidationException.class);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.MATCH_REVIEW);
    }

    @Test
    void resolve_entryNotAwaitingReview_throwsValidationException() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        JournalEntry entry = ourJournalEntry();
        entry.confirmMatchByReviewer(5L);
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));

        assertThatThrownBy(() ->
                matchingResultService.resolve(1L, "not_ours", null, "kakao-teacher-1", OUR_USER_ID))
                .isInstanceOf(MatchingResultValidationException.class);
        assertThat(matchingResult.getStatus()).isEqualTo(MatchingStatus.REVIEW);
        assertThat(entry.getChildId()).isEqualTo(5L);
    }

    private JournalEntry validationFailedEntry() {
        JournalEntry entry = JournalEntry.of(3L, LocalDate.of(2026, 9, 1), "점심시간에 식사를 잘함", 1);
        entry.startMatching();
        entry.confirmMatch(5L);
        entry.startValidating();
        ReflectionTestUtils.setField(entry, "status", JournalEntryStatus.FAILED);
        return entry;
    }

    @ParameterizedTest
    @ValueSource(strings = {"assign", "not_ours"})
    void resolve_alreadyAutoResult_throwsValidationException(String action) {
        MatchingResult matchingResult = new MatchingResult(
                1L, 5L, new BigDecimal("0.95"), MatchingStatus.AUTO, null, null, null, null, null, null, "v1");
        JournalEntry entry = validationFailedEntry();
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        lenient().when(childRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(child()));
        lenient().when(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(2L, OUR_ORG_ID))
                .thenReturn(true);
        lenient().when(sharingConsentRepository.existsByChildIdAndOrganizationIdAndStatus(
                        2L, OUR_ORG_ID, SharingConsentStatus.ACTIVE))
                .thenReturn(true);

        assertThatThrownBy(() ->
                matchingResultService.resolve(1L, action, 2L, "kakao-teacher-1", OUR_USER_ID))
                .isInstanceOf(MatchingResultValidationException.class);
        assertThat(matchingResult.getMatchedChildId()).isEqualTo(5L);
        assertThat(matchingResult.getReviewerId()).isNull();
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.FAILED);
        assertThat(entry.getChildId()).isEqualTo(5L);
    }

    @Test
    void resolveAssign_matchingFailedEntry_setsMatchedChild() {
        MatchingResult matchingResult = MatchingResult.failed(1L);
        JournalEntry entry = JournalEntry.of(3L, LocalDate.of(2026, 9, 1), "점심시간에 식사를 잘함", 1);
        entry.startMatching();
        entry.failMatching();
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(childRepository.findByIdForUpdate(2L)).willReturn(Optional.of(child()));
        given(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(2L, OUR_ORG_ID))
                .willReturn(true);
        consentedToOurOrganization(true);
        given(matchingResultRepository.save(any(MatchingResult.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MatchingQueueItemResponse resolved =
                matchingResultService.resolve(1L, "assign", 2L, "kakao-teacher-1", OUR_USER_ID);

        assertThat(resolved.status()).isEqualTo(MatchingStatus.AUTO);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
        assertThat(entry.getChildId()).isEqualTo(2L);
    }

    @Test
    void resolveAssign_unknownChildId_throwsValidationException() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(childRepository.findByIdForUpdate(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                matchingResultService.resolve(1L, "assign", 999L, "kakao-teacher-1", OUR_USER_ID))
                .isInstanceOf(MatchingResultValidationException.class)
                .hasMessage("child not available: 999");
    }

    @Test
    void resolveAssign_childNotInThisInstitution_throwsValidationException() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(childRepository.findByIdForUpdate(2L))
                .willReturn(Optional.of(Child.of("김하늘", LocalDate.of(2020, 1, 1))));
        given(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(2L, OUR_ORG_ID))
                .willReturn(false);

        assertThatThrownBy(() ->
                matchingResultService.resolve(1L, "assign", 2L, "kakao-teacher-1", OUR_USER_ID))
                .isInstanceOf(MatchingResultValidationException.class)
                .hasMessage("child not available: 2");
    }

    @Test
    void resolveAssignWithoutChildId_throwsValidationException() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));

        assertThatThrownBy(() ->
                matchingResultService.resolve(1L, "assign", null, "kakao-teacher-1", OUR_USER_ID))
                .isInstanceOf(MatchingResultValidationException.class);
    }

    @Test
    void resolveNotOurs_clearsMatchedChildAndLeavesQueue() {
        MatchingResult matchingResult = new MatchingResult(
                1L, 5L, new BigDecimal("0.3"), MatchingStatus.MULTI, null, null, null, null, null, null, "v1");
        JournalEntry entry = ourJournalEntry();
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(matchingResultRepository.save(any(MatchingResult.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MatchingQueueItemResponse resolved =
                matchingResultService.resolve(1L, "not_ours", null, "kakao-teacher-1", OUR_USER_ID);

        assertThat(resolved.matchedChildId()).isNull();
        assertThat(resolved.status()).isEqualTo(MatchingStatus.AUTO);
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.EXCLUDED);
        assertThat(entry.getChildId()).isNull();
    }

    @Test
    void resolveUnknownId_throwsNotFound() {
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                matchingResultService.resolve(999L, "assign", 1L, "kakao-teacher-1", OUR_USER_ID))
                .isInstanceOf(MatchingResultNotFoundException.class);
    }

    @Test
    void resolveOtherInstitutionsEntry_throwsNotFound() {
        MatchingResult theirs = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(theirs));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OTHER_INSTITUTION)));

        assertThatThrownBy(() ->
                matchingResultService.resolve(1L, "assign", 2L, "kakao-teacher-1", OUR_USER_ID))
                .isInstanceOf(MatchingResultNotFoundException.class);
    }
}
