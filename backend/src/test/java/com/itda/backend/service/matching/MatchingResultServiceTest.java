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

@ExtendWith(MockitoExtension.class)
class MatchingResultServiceTest {

    // principal은 userId다. 실제 기관 식별자(RawRecord.institutionId와 비교되는 값)는
    // userService.getOrganizationIdOf(userId)로 풀린다 — RawRecordService와 같은 계약(PR #43).
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

    private MatchingResultService matchingResultService;

    @BeforeEach
    void setUp() {
        matchingResultService = new MatchingResultService(
                matchingResultRepository, journalEntryRepository, rawRecordRepository,
                childRepository, childOrganizationRepository, sharingConsentRepository, userService);
    }

    // 확인 필요 큐에 뜨는 일지 — 워커가 AI 에 물어봤는데 확정하지 못한 상태.
    private JournalEntry ourJournalEntry() {
        JournalEntry entry = JournalEntry.of(3L, LocalDate.of(2026, 9, 1), "점심시간에 식사를 잘함", 1);
        entry.startMatching();
        entry.requestMatchReview();
        return entry;
    }

    // 매칭 확정은 child.status 가 아니라 이 기관에 대한 동의(sharing_consent)를 본다.
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

    // 코드리뷰 반영(멘토 PR #86, #111): 목록 미리보기(preview)는 60자로 그대로 유지하되,
    // 아이 선택 시 펼쳐 볼 전체 본문(fullContent)을 별도로 내려줘서 뒤에 나오는 아이 이름·
    // AI 판단 근거를 선생님이 놓치지 않게 한다.
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
        // 일지가 이미 처리됐으면(MATCHED 등) 남아 있는 옛 결과 행은 눌러도 거절되니 큐에 띄우지 않는다.
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
        // 버그 재발 방지: AI가 candidates에 child_id/confidence 중 하나라도 빠뜨려 쓰면
        // node.get(...).asLong()가 NPE를 던져서 큐 조회 전체가 500 났었다.
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
        // 일지도 AI 자동 확정과 같은 상태가 돼야 검증 단계가 이어서 가져간다.
        assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
        assertThat(entry.getChildId()).isEqualTo(2L);
    }

    @Test
    void resolveAssign_pendingConsentChild_throwsValidationException() {
        // AI 명단(findConsentedByOrganizationId)에서 빠지는 동의 전 아동은 사람이 직접 골라도 확정할 수 없다.
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
        // 다른 기관에만 동의해 child.status 가 ACTIVE 인 아이도 이 기관에서는 확정할 수 없다.
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
        // 이미 처리된 일지(예: 다른 선생님이 먼저 확정)는 다시 처리하지 않는다.
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

    // 매칭은 AUTO로 끝났는데 검증 AI 호출이 실패해서 FAILED가 된 일지. #180부터 검증 실패는 SYSTEM_ERROR가 되지만
    // 그 전에 FAILED로 남은 일지가 배포 DB에 있다. 지금 코드로는 만들 수 없어 상태를 직접 넣는다.
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
        // 일지 FAILED는 매칭 실패뿐 아니라 #180 이전의 검증 실패로도 생겼다(#107). 매칭 결과가 이미 AUTO면
        // 이미 처리된 결과라 일지 상태가 FAILED여도 다시 처리하지 않는다.
        MatchingResult matchingResult = new MatchingResult(
                1L, 5L, new BigDecimal("0.95"), MatchingStatus.AUTO, null, null, null, null, null, null, "v1");
        JournalEntry entry = validationFailedEntry();
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        // assign이 아이 검증 때문에 거절되는 게 아니라는 걸 보장하려고 처리 가능한 아이로 둔다.
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
        // 매칭 AI 호출이 실패한 일지(결과도 FAILED)는 지금처럼 사람이 처리할 수 있어야 한다.
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
                .isInstanceOf(MatchingResultValidationException.class);
    }

    @Test
    void resolveAssign_childNotInThisInstitution_throwsValidationException() {
        // 아이는 존재하지만 우리 기관 소속이 아닌 경우(다른 기관 아이를 잘못 assign) — PR #43로
        // child_organization이 실제 organizationId를 쓰게 돼서 비로소 이 체크가 가능해졌다.
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
                .isInstanceOf(MatchingResultValidationException.class);
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
        // 버그 재발 방지: 예전엔 여기서 status를 UNMATCHED로 뒀는데, UNMATCHED는
        // findByStatusNot(AUTO) 큐 조건에 여전히 걸려서 "제외" 처리해도 큐에서 안 빠졌다.
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
        // 일지는 제외 상태로 끝낸다 — MATCH_REVIEW 에 남으면 파이프라인에서 멈춘 채로 보인다.
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
