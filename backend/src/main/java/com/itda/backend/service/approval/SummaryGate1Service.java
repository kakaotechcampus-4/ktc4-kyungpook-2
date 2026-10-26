package com.itda.backend.service.approval;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.Gate1Action;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.dto.request.summary.Gate1DecisionRequest.Card;
import com.itda.backend.dto.response.summary.Gate1DecisionResponse;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.exception.summary.SummaryErrorCode;
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
     * @param cards   승인할 때 문장 카드. 없거나 모두 원래 순서대로 유지면 그대로 승인이다 ({@link Gate1Cards})
     * @param version 교사가 목록에서 받은 판 표시 ({@link Gate1Version})
     */
    @Transactional
    public Gate1DecisionResponse decide(String userId, Long summaryId, Gate1Action action, List<Card> cards,
            String version) {
        SummaryResult summary = lockOwnedSummary(userId, summaryId);
        requireDecidable(summary, action);
        // 보류된 판은 워커가 덮어쓰지 않는다(새 일지는 새 판이 된다). 보류함에서 제거할 때는 볼 필요가 없다.
        if (summary.getStatus() == SummaryStatus.GATE1_PENDING) {
            requireNotRegenerating(summary);
        }
        List<JournalEntry> entries = journalEntryRepository.findBySummaryIdAndDeletedAtIsNull(summaryId);
        // 교사가 본 뒤 워커가 같은 요약을 새 일지까지 묶어 덮어썼다. 그대로 받으면 교사가 못 본 일지가 승인과 함께
        // 끝나거나(승인) 지워진다(제거).
        if (!Gate1Version.of(summary, entries).equals(version)) {
            throw new SummaryException(SummaryErrorCode.CHANGED, summaryId);
        }
        ApprovalDecision decision = switch (action) {
            case APPROVE -> approve(summary, entries, cards);
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
            throw new SummaryException(SummaryErrorCode.NOT_HELD, summaryId);
        }
        requireNotRegenerating(summary);
        summary.reopen();
        journalEntryRepository.findBySummaryIdAndDeletedAtIsNull(summaryId).stream()
                .filter(e -> e.getStatus() == JournalEntryStatus.GATE1_HELD)
                .forEach(JournalEntry::reopenGate1);
        approvalRepository.save(Approval.gate1(summaryId, Long.valueOf(userId), ApprovalDecision.REOPENED));
        return new Gate1DecisionResponse(summaryId, summary.getStatus().name(), ApprovalDecision.REOPENED.name());
    }

    /**
     * 카드가 없으면 그대로 승인이다. 카드로 하나라도 바꿨으면 claims 를 카드 결과로 다시 쓰고 남은 문장을 이은 글을 올린다
     * ({@link Gate1Cards}). 요약 행의 본문과 AI 응답 원문은 그대로 둔다.
     */
    private ApprovalDecision approve(SummaryResult summary, List<JournalEntry> entries, List<Card> cards) {
        Gate1Cards.Applied applied = Gate1Cards.apply(summary, cards);
        if (applied.corrected()) {
            summary.approveWithClaims(applied.claims());
        } else {
            summary.approve();
        }
        childContextRepository.save(ChildContext.of(summary.getChildId(), summary.getId(), applied.content()));
        // 요약에 안 들어간 일지도 같은 요약을 가리킨다. 함께 끝내지 않으면 다음 요약에 다시 묶인다.
        pendingOf(entries).forEach(JournalEntry::completeGate1);
        return applied.corrected() ? ApprovalDecision.CORRECTED : ApprovalDecision.APPROVED;
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

    /** 검토 대기는 모든 결정을, 보류함은 제거만 받는다. 보류된 요약의 승인은 보류를 해제한 뒤 검토 목록에서 한다. */
    private static void requireDecidable(SummaryResult summary, Gate1Action action) {
        if (summary.getStatus() == SummaryStatus.GATE1_PENDING) {
            return;
        }
        if (summary.getStatus() == SummaryStatus.HOLD) {
            if (action == Gate1Action.REMOVE) {
                return;
            }
            throw new SummaryException(SummaryErrorCode.HELD, summary.getId());
        }
        throw new SummaryException(SummaryErrorCode.ALREADY_REVIEWED, summary.getId());
    }

    /**
     * 같은 묶음을 워커가 요약하는 중이면 결정을 받지 않는다. 요약 결과는 그 묶음의 최신 판이 대기면 덮어쓰므로, 지금
     * 승인하면 곧 바뀔 글이 올라가고 다시 만든 글은 새 판으로 쌓여 같은 일지가 두 번 올라간다. 보류 해제도 같다 —
     * 해제한 판이 새 일지만 담은 결과로 덮어써질 수 있다. 이 판의 일지가 집히지 않았어도 묶음 단위로 본다.
     * 최신 판이 아니면 워커가 덮어쓰지 않으므로 보지 않는다.
     *
     * <p>워커도 묶음을 집을 때 이 요약 행을 잠그므로, 여기서 잠근 뒤 보는 일지 상태는 커밋된 최신 값이다.
     */
    private void requireNotRegenerating(SummaryResult summary) {
        if (summaryResultRepository.existsNewerRevision(
                summary.getChildId(), summary.getEntryDate(), summary.getInstitutionId(), summary.getRevision())) {
            return;
        }
        if (!journalEntryRepository.findGroupEntryIds(JournalEntryStatus.SUMMARIZING, summary.getChildId(),
                summary.getEntryDate(), String.valueOf(summary.getInstitutionId())).isEmpty()) {
            throw new SummaryException(SummaryErrorCode.REGENERATING, summary.getId());
        }
    }

    private static List<JournalEntry> pendingOf(List<JournalEntry> entries) {
        return entries.stream().filter(e -> e.getStatus() == JournalEntryStatus.GATE1_PENDING).toList();
    }

    private SummaryResult lockOwnedSummary(String userId, Long summaryId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        // 다른 기관 요약이면 "권한 없음"이 아니라 "없음"으로 응답한다. 기관 조건을 잠금 조회에 넣어, 남의 요약 행은
        // 잠그지 않는다 — 잠그면 그 기관의 결정과 워커가 기다리고, 기다린 시간으로 id 가 있는지도 드러난다.
        return summaryResultRepository.findByIdAndInstitutionIdForUpdate(summaryId, organizationId)
                .orElseThrow(() -> new SummaryException(SummaryErrorCode.NOT_FOUND, summaryId));
    }
}
