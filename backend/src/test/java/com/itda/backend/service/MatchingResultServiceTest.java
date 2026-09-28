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

import com.itda.backend.domain.Child;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.dto.MatchingQueueItemResponse;
import com.itda.backend.exception.MatchingResultNotFoundException;
import com.itda.backend.exception.MatchingResultValidationException;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.MatchingResultRepository;
import com.itda.backend.repository.RawRecordRepository;

@ExtendWith(MockitoExtension.class)
class MatchingResultServiceTest {

    private static final String OUR_INSTITUTION = "kakao-our-institution";
    private static final String OTHER_INSTITUTION = "kakao-other-institution";

    @Mock
    private MatchingResultRepository matchingResultRepository;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private RawRecordRepository rawRecordRepository;
    @Mock
    private ChildRepository childRepository;

    private MatchingResultService matchingResultService;

    @BeforeEach
    void setUp() {
        matchingResultService = new MatchingResultService(
                matchingResultRepository, journalEntryRepository, rawRecordRepository, childRepository);
    }

    private JournalEntry ourJournalEntry() {
        return JournalEntry.of(3L, LocalDate.of(2026, 9, 1), "점심시간에 식사를 잘함", 1);
    }

    private RawRecord ourRawRecord(String institutionId) {
        return new RawRecord(institutionId, "0821_관찰일지.docx", "stored.docx", "text/plain", 10L, RawRecordStatus.PENDING);
    }

    @Test
    void getQueue_includesOnlyOwnInstitutionsEntries() {
        MatchingResult ours = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, "v1");
        given(matchingResultRepository.findByStatusNot(MatchingStatus.AUTO)).willReturn(List.of(ours));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findById(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));

        List<MatchingQueueItemResponse> queue = matchingResultService.getQueue(OUR_INSTITUTION);

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).record().fileName()).isEqualTo("0821_관찰일지.docx");
    }

    @Test
    void getQueue_skipsMalformedCandidateInsteadOfThrowing() {
        // 버그 재발 방지: AI가 candidates에 child_id/confidence 중 하나라도 빠뜨려 쓰면
        // node.get(...).asLong()가 NPE를 던져서 큐 조회 전체가 500 났었다.
        MatchingResult ours = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.MULTI, null, null,
                "[{\"confidence\":0.8}]", null, null, "v1");
        given(matchingResultRepository.findByStatusNot(MatchingStatus.AUTO)).willReturn(List.of(ours));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findById(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));

        List<MatchingQueueItemResponse> queue = matchingResultService.getQueue(OUR_INSTITUTION);

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).candidates()).isEmpty();
    }

    @Test
    void getQueue_excludesOtherInstitutionsEntries() {
        MatchingResult theirs = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, "v1");
        given(matchingResultRepository.findByStatusNot(MatchingStatus.AUTO)).willReturn(List.of(theirs));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findById(3L)).willReturn(Optional.of(ourRawRecord(OTHER_INSTITUTION)));

        List<MatchingQueueItemResponse> queue = matchingResultService.getQueue(OUR_INSTITUTION);

        assertThat(queue).isEmpty();
    }

    @Test
    void resolveAssign_setsMatchedChildAndAutoStatus() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, "v1");
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findById(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(childRepository.findByIdAndDeletedAtIsNull(2L))
                .willReturn(Optional.of(Child.of("김하늘", LocalDate.of(2020, 1, 1))));
        given(matchingResultRepository.save(any(MatchingResult.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MatchingQueueItemResponse resolved =
                matchingResultService.resolve(1L, "assign", 2L, "kakao-teacher-1", OUR_INSTITUTION);

        assertThat(resolved.matchedChildId()).isEqualTo("2");
        assertThat(resolved.status()).isEqualTo(MatchingStatus.AUTO);
    }

    @Test
    void resolveAssign_unknownChildId_throwsValidationException() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, "v1");
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findById(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(childRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                matchingResultService.resolve(1L, "assign", 999L, "kakao-teacher-1", OUR_INSTITUTION))
                .isInstanceOf(MatchingResultValidationException.class);
    }

    @Test
    void resolveAssignWithoutChildId_throwsValidationException() {
        MatchingResult matchingResult = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, "v1");
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findById(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));

        assertThatThrownBy(() ->
                matchingResultService.resolve(1L, "assign", null, "kakao-teacher-1", OUR_INSTITUTION))
                .isInstanceOf(MatchingResultValidationException.class);
    }

    @Test
    void resolveNotOurs_clearsMatchedChildAndLeavesQueue() {
        // 버그 재발 방지: 예전엔 여기서 status를 UNMATCHED로 뒀는데, UNMATCHED는
        // findByStatusNot(AUTO) 큐 조건에 여전히 걸려서 "제외" 처리해도 큐에서 안 빠졌다.
        MatchingResult matchingResult = new MatchingResult(
                1L, 5L, new BigDecimal("0.3"), MatchingStatus.MULTI, null, null, null, null, null, "v1");
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(matchingResult));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findById(3L)).willReturn(Optional.of(ourRawRecord(OUR_INSTITUTION)));
        given(matchingResultRepository.save(any(MatchingResult.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MatchingQueueItemResponse resolved =
                matchingResultService.resolve(1L, "not_ours", null, "kakao-teacher-1", OUR_INSTITUTION);

        assertThat(resolved.matchedChildId()).isNull();
        assertThat(resolved.status()).isEqualTo(MatchingStatus.AUTO);
    }

    @Test
    void resolveUnknownId_throwsNotFound() {
        given(matchingResultRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                matchingResultService.resolve(999L, "assign", 1L, "kakao-teacher-1", OUR_INSTITUTION))
                .isInstanceOf(MatchingResultNotFoundException.class);
    }

    @Test
    void resolveOtherInstitutionsEntry_throwsNotFound() {
        MatchingResult theirs = new MatchingResult(
                1L, null, new BigDecimal("0.4"), MatchingStatus.REVIEW, null, null, null, null, null, "v1");
        given(matchingResultRepository.findById(1L)).willReturn(Optional.of(theirs));
        given(journalEntryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(ourJournalEntry()));
        given(rawRecordRepository.findById(3L)).willReturn(Optional.of(ourRawRecord(OTHER_INSTITUTION)));

        assertThatThrownBy(() ->
                matchingResultService.resolve(1L, "assign", 2L, "kakao-teacher-1", OUR_INSTITUTION))
                .isInstanceOf(MatchingResultNotFoundException.class);
    }
}
