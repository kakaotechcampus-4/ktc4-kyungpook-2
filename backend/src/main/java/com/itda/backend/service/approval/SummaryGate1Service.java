package com.itda.backend.service.approval;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.dto.response.summary.Gate1DecisionResponse;
import com.itda.backend.exception.summary.SummaryConflictException;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryNotFoundException;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * Gate 1 — 교사가 요약을 승인·수정 후 승인·보류·제거하고, 보류함에서 보류를 해제한다 (DB 스키마 §8.2, §9.1).
 *
 * <p>한 기관에 담당자가 한 명이라 권한은 기관 소속만 본다. 결정마다 {@code human_review} 에 한 줄 남긴다.
 * 동시에 두 결정이 와도 한쪽만 반영되도록 요약 행을 잠그고 상태를 본다. 승인은 {@code child_context} 의
 * {@code summary_result_id} UNIQUE 가 마지막으로 한 번 더 막는다.
 */
@Service
@RequiredArgsConstructor
public class SummaryGate1Service {

    private final UserService userService;
    private final SummaryResultRepository summaryResultRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final ChildContextRepository childContextRepository;
    private final ApprovalRepository approvalRepository;

    /**
     * Gate 1 결정을 반영한다. 승인하면 그 요약의 일지가 요약에 안 들어간 것까지 모두 끝나고, 보류하면 함께 보류되고,
     * 제거하면 모두 삭제 표시된다.
     *
     * @param editedContent 승인할 때 고친 본문. 없거나 AI 원문과 같으면 그대로 승인이다.
     */
    @Transactional
    public Gate1DecisionResponse decide(String userId, Long summaryId, Gate1Action action, String editedContent) {
        SummaryResult summary = lockOwnedSummary(userId, summaryId);
        if (summary.getStatus() != SummaryStatus.GATE1_PENDING) {
            throw new SummaryConflictException(SummaryErrorCode.ALREADY_REVIEWED, summaryId);
        }
        // 워커가 새 일지와 함께 다시 요약하는 중이면 곧 본문이 바뀐다. 지금 승인하면 바뀌기 전 글이 올라가고,
        // 다시 만든 글은 새 판으로 쌓여 같은 일지가 두 번 올라간다.
        if (journalEntryRepository.existsBySummaryIdAndStatusAndDeletedAtIsNull(summaryId,
                JournalEntryStatus.SUMMARIZING)) {
            throw new SummaryConflictException(SummaryErrorCode.REGENERATING, summaryId);
        }

        List<JournalEntry> entries = journalEntryRepository.findBySummaryIdAndDeletedAtIsNull(summaryId);
        ApprovalDecision decision = switch (action) {
            case APPROVE -> approve(summary, entries, editedContent);
            case HOLD -> hold(summary, entries);
            case REMOVE -> remove(summary, entries);
        };
        approvalRepository.save(Approval.gate1(summaryId, Long.valueOf(userId), decision));
        return new Gate1DecisionResponse(summaryId, summary.getStatus().name(), decision.name());
    }

    /** 보류함에서 보류를 해제한다. 요약과 그 일지가 다시 Gate 1 대기로 돌아온다. */
    @Transactional
    public Gate1DecisionResponse reopen(String userId, Long summaryId) {
        SummaryResult summary = lockOwnedSummary(userId, summaryId);
        if (summary.getStatus() != SummaryStatus.HOLD) {
            throw new SummaryConflictException(SummaryErrorCode.NOT_HELD, summaryId);
        }
        summary.reopen();
        journalEntryRepository.findBySummaryIdAndDeletedAtIsNull(summaryId).stream()
                .filter(e -> e.getStatus() == JournalEntryStatus.GATE1_HELD)
                .forEach(JournalEntry::reopenGate1);
        approvalRepository.save(Approval.gate1(summaryId, Long.valueOf(userId), ApprovalDecision.REOPENED));
        return new Gate1DecisionResponse(summaryId, summary.getStatus().name(), ApprovalDecision.REOPENED.name());
    }

    private ApprovalDecision approve(SummaryResult summary, List<JournalEntry> entries, String editedContent) {
        boolean corrected = editedContent != null && !editedContent.equals(summary.getContent());
        String finalContent = corrected ? editedContent : summary.getContent();
        summary.approve();
        childContextRepository.save(ChildContext.of(summary.getChildId(), summary.getId(), finalContent));
        // 요약에 안 들어간 일지도 같은 요약을 가리킨다. 함께 끝내지 않으면 다음 요약에 다시 묶인다.
        pendingOf(entries).forEach(JournalEntry::completeGate1);
        return corrected ? ApprovalDecision.CORRECTED : ApprovalDecision.APPROVED;
    }

    private ApprovalDecision hold(SummaryResult summary, List<JournalEntry> entries) {
        summary.hold();
        pendingOf(entries).forEach(JournalEntry::holdGate1);
        return ApprovalDecision.HOLD;
    }

    private ApprovalDecision remove(SummaryResult summary, List<JournalEntry> entries) {
        summary.remove();
        entries.forEach(JournalEntry::delete);
        return ApprovalDecision.REMOVED;
    }

    private static List<JournalEntry> pendingOf(List<JournalEntry> entries) {
        return entries.stream().filter(e -> e.getStatus() == JournalEntryStatus.GATE1_PENDING).toList();
    }

    private SummaryResult lockOwnedSummary(String userId, Long summaryId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        // 다른 기관 요약이면 "권한 없음"이 아니라 "없음"으로 응답한다
        return summaryResultRepository.findByIdForUpdate(summaryId)
                .filter(s -> s.getInstitutionId().equals(organizationId))
                .orElseThrow(() -> new SummaryNotFoundException(summaryId));
    }
}
