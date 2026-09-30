package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildStatus;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;
import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.dto.MatchingQueueItemResponse;
import com.itda.backend.exception.MatchingResultNotFoundException;
import com.itda.backend.exception.MatchingResultValidationException;
import com.itda.backend.repository.ChildOrganizationRepository;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.MatchingResultRepository;
import com.itda.backend.repository.RawRecordRepository;

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
    private UserService userService;

    private MatchingResultService matchingResultService;

    @BeforeEach
    void setUp() {
        matchingResultService = new MatchingResultService(
                matchingResultRepository, journalEntryRepository, rawRecordRepository,
                childRepository, childOrganizationRepository, userService);
    }

    // 확인 필요 큐에 뜨는 일지 — 워커가 AI 에 물어봤는데 확정하지 못한 상태.
    private JournalEntry ourJournalEntry() {
        JournalEntry entry = JournalEntry.of(3L, LocalDate.of(2026, 9, 1), "점심시간에 식사를 잘함", 1);
        entry.startMatching();
        entry.requestMatchReview();
        return entry;
    }

    // 아동은 동의 전으로 만들어지고 동의 완료로 바꾸는 메서드는 아직 없다.
    private Child activeChild() {
        Child child = Child.of("김하늘", LocalDate.of(2020, 1, 1));
        ReflectionTestUtils.setField(child, "status", ChildStatus.ACTIVE);
        return child;
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
        given(childRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(activeChild()));
        given(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(2L, OUR_ORG_ID))
                .willReturn(true);
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
        // AI 명단(findActiveByOrganizationId)에서 빠지는 동의 전 아동은 사람이 직접 골라도 확정할 수 없다.
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.UNMATCHED, null, null, null, null, null, null, "v1");
        JournalEntry entry = ourJournalEntry();
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entry));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(childRepository.findByIdAndDeletedAtIsNull(2L))
                .willReturn(Optional.of(Child.of("김하늘", LocalDate.of(2020, 1, 1))));
        given(childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(2L, OUR_ORG_ID))
                .willReturn(true);

        assertThatThrownBy(() ->
                matchingResultService.resolve(1L, "assign", 2L, "kakao-teacher-1", OUR_USER_ID))
                .isInstanceOf(MatchingResultValidationException.class);
        assertThat(matchingResult.getStatus()).isEqualTo(MatchingStatus.UNMATCHED);
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

    @Test
    void resolveAssign_unknownChildId_throwsValidationException() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, null, "v1");
        given(userService.getOrganizationIdOf(OUR_USER_ID)).willReturn(OUR_ORG_ID);
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findByIdAndDeletedAtIsNull(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(childRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

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
        given(childRepository.findByIdAndDeletedAtIsNull(2L))
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
