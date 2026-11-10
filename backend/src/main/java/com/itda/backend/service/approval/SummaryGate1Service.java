package com.itda.backend.service.approval;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.Gate1Action;
import com.itda.backend.domain.consent.ConsentState;
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
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.agent.AgentQueueGuard;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SummaryGate1Service {

    private final UserService userService;
    private final SummaryResultRepository summaryResultRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final ChildContextRepository childContextRepository;
    private final ApprovalRepository approvalRepository;
    private final ChildRepository childRepository;
    private final ConsentStateReader consentStateReader;
    private final AgentQueueGuard agentQueueGuard;

    @Transactional
    public Gate1DecisionResponse decide(String userId, Long summaryId, Gate1Action action, List<Card> cards,
            String version) {
        SummaryResult summary = lockOwnedSummary(userId, summaryId);
        if (isClosed(summary)) {
            throw new SummaryException(SummaryErrorCode.ALREADY_REVIEWED, summaryId);
        }
        requireConsented(summary);
        requireDecidable(summary, action);
        if (summary.getStatus() == SummaryStatus.GATE1_PENDING) {
            requireNotRegenerating(summary);
        }
        List<JournalEntry> entries = journalEntryRepository.findBySummaryIdAndDeletedAtIsNull(summaryId);
        if (!Gate1Version.of(summary, entries).equals(version)) {
            throw new SummaryException(SummaryErrorCode.CHANGED, summaryId);
        }
        requireDecisionFits(summary, action);
        ApprovalDecision decision = switch (action) {
            case APPROVE -> approve(summary, entries, cards);
            case HOLD -> hold(summary, entries);
            case REMOVE -> remove(summary, entries);
            case CONFIRM -> confirm(summary, entries);
            case REVERT -> revert(summary, entries);
        };
        approvalRepository.save(Approval.gate1(summaryId, Long.valueOf(userId), decision));
        return new Gate1DecisionResponse(summaryId, summary.getStatus().name(), decision.name());
    }

    @Transactional
    public Gate1DecisionResponse reopen(String userId, Long summaryId) {
        SummaryResult summary = lockOwnedSummary(userId, summaryId);
        if (isClosed(summary)) {
            throw new SummaryException(SummaryErrorCode.NOT_HELD, summaryId);
        }
        requireConsented(summary);
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

    private ApprovalDecision approve(SummaryResult summary, List<JournalEntry> entries, List<Card> cards) {
        Gate1Cards.Applied applied = Gate1Cards.apply(summary, cards);
        if (applied.corrected()) {
            summary.approveWithClaims(applied.claims());
        } else {
            summary.approve();
        }
        childContextRepository.save(ChildContext.of(summary.getChildId(), summary.getId(), applied.content()));
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

    private ApprovalDecision confirm(SummaryResult summary, List<JournalEntry> entries) {
        summary.confirmNoContent();
        pendingOf(entries).forEach(JournalEntry::completeGate1);
        return ApprovalDecision.CONFIRMED;
    }

    private ApprovalDecision revert(SummaryResult summary, List<JournalEntry> entries) {
        List<JournalEntry> pending = pendingOf(entries);
        agentQueueGuard.requireRoom(summary.getInstitutionId(), pending.size());
        summary.revertNoContent();
        pending.forEach(JournalEntry::revertToSummaryWait);
        return ApprovalDecision.REVERTED;
    }

    private static void requireDecisionFits(SummaryResult summary, Gate1Action action) {
        boolean noContentAction = action == Gate1Action.CONFIRM || action == Gate1Action.REVERT;
        if (summary.isNoContent() != noContentAction) {
            throw new SummaryException(SummaryErrorCode.INVALID_REQUEST,
                    "summaryId=" + summary.getId() + " decision=" + action + " noContent=" + summary.isNoContent());
        }
    }

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

    private void requireConsented(SummaryResult summary) {
        childRepository.findByIdForUpdate(summary.getChildId());
        if (consentStateReader.stateOf(summary.getChildId(), summary.getInstitutionId()) != ConsentState.GRANTED) {
            throw new SummaryException(SummaryErrorCode.NOT_FOUND, summary.getId());
        }
    }

    private static boolean isClosed(SummaryResult summary) {
        return summary.getStatus() != SummaryStatus.GATE1_PENDING && summary.getStatus() != SummaryStatus.HOLD;
    }

    private static List<JournalEntry> pendingOf(List<JournalEntry> entries) {
        return entries.stream().filter(e -> e.getStatus() == JournalEntryStatus.GATE1_PENDING).toList();
    }

    private SummaryResult lockOwnedSummary(String userId, Long summaryId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        return summaryResultRepository.findByIdAndInstitutionIdForUpdate(summaryId, organizationId)
                .orElseThrow(() -> new SummaryException(SummaryErrorCode.NOT_FOUND, summaryId));
    }
}
