package com.itda.backend.service.summary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.request.summary.SummaryAgentRequest;
import com.itda.backend.dto.response.summary.SummaryAgentResponse;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.service.consent.ConsentStateReader;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryResultRecorder {

    private final JournalEntryRepository journalEntryRepository;
    private final SummaryResultRepository summaryResultRepository;
    private final SummaryEvidenceChecker evidenceChecker;
    private final SummaryRunRequests runRequests;
    private final ConsentStateReader consentStateReader;
    private final ChildRepository childRepository;

    @Transactional
    public boolean closeIfConsentRevoked(ClaimedSummaryGroup claimed) {
        SummaryGroup group = claimed.group();
        if (consentStateReader.stateOf(group.childId(), group.institutionId()) == ConsentState.GRANTED) {
            return false;
        }
        log.info("consent revoked, summary group closed without summarizing group={} journalEntryIds={}",
                group, claimed.journalEntryIds());
        runRequests.markHandled(group, claimed.claimedAt());
        summarizingEntries(claimed).forEach(JournalEntry::releaseSummarizing);
        return true;
    }

    @Transactional
    public void record(ClaimedSummaryGroup claimed, SummaryAgentRequest request, SummaryAgentReply reply) {
        if (lockAndCloseIfRevoked(claimed)) {
            return;
        }
        SummaryAgentResponse response = reply.response();
        runRequests.markHandled(claimed.group(), claimed.claimedAt());
        if (!response.isNoContent() && isEmptyContent(response)) {
            log.warn("summary returned empty content, recorded as system error group={}", claimed.group());
            failBySystemError(claimed, FailureReason.RESPONSE_UNREADABLE);
            return;
        }
        List<JournalEntry> entries = summarizingEntries(claimed);
        if (entries.isEmpty()) {
            log.warn("all journal entries deleted during summary, result dropped group={}", claimed.group());
            return;
        }
        Set<Long> alive = entries.stream().map(JournalEntry::getId).collect(Collectors.toSet());
        Map<Long, String> sources = request.sources().stream()
                .filter(source -> alive.contains(source.journalEntryId()))
                .collect(Collectors.toMap(SummaryAgentRequest.Source::journalEntryId,
                        SummaryAgentRequest.Source::content));
        Set<Long> sentIds = request.sources().stream()
                .map(SummaryAgentRequest.Source::journalEntryId)
                .collect(Collectors.toSet());
        Optional<String> problem = evidenceChecker.findProblem(claimed.group(), sentIds, sources, response);
        if (problem.isPresent()) {
            log.warn("summary evidence check failed, recorded as system error group={} journalEntryIds={} reason={}",
                    claimed.group(), claimed.journalEntryIds(), problem.get());
            failBySystemError(claimed, FailureReason.EVIDENCE_MISMATCH);
            return;
        }

        SummaryGroup group = claimed.group();
        SummaryResult latest = summaryResultRepository
                .findFirstByChildIdAndEntryDateAndInstitutionIdOrderByRevisionDesc(
                        group.childId(), group.entryDate(), group.institutionId())
                .orElse(null);
        SummaryResult summary = response.isNoContent()
                ? saveNoContent(group, latest, entries, reply)
                : saveSummary(group, latest, reply);
        entries.forEach(entry -> entry.completeSummary(summary.getId()));
    }

    private SummaryResult saveSummary(SummaryGroup group, SummaryResult latest, SummaryAgentReply reply) {
        SummaryAgentResponse response = reply.response();
        String claims = toJson(response.claims());
        String covered = toJson(response.coveredEntryIds());
        String uncovered = toJson(response.uncoveredEntryIds());
        SummaryResult summary = saveRevision(latest,
                pending -> pending.overwrite(response.content(), claims, covered, uncovered, reply.rawJson()),
                revision -> SummaryResult.of(group.childId(), group.entryDate(), group.institutionId(), revision,
                        response.content(), claims, covered, uncovered, reply.rawJson()));
        JsonNode reasons = response.reviewReasons();
        boolean needsReview = Boolean.TRUE.equals(response.needsReview())
                || (reasons != null && reasons.isArray() && !reasons.isEmpty());
        summary.markReview(needsReview, toJson(reasons));
        return summary;
    }

    @Transactional
    public void recordFailure(ClaimedSummaryGroup claimed) {
        if (lockAndCloseIfRevoked(claimed)) {
            return;
        }
        runRequests.markHandled(claimed.group(), claimed.claimedAt());
        summarizingEntries(claimed).forEach(JournalEntry::failSummary);
    }

    @Transactional
    public void recordSystemError(ClaimedSummaryGroup claimed, FailureReason reason) {
        if (lockAndCloseIfRevoked(claimed)) {
            return;
        }
        runRequests.markHandled(claimed.group(), claimed.claimedAt());
        failBySystemError(claimed, reason);
    }

    private void failBySystemError(ClaimedSummaryGroup claimed, FailureReason reason) {
        summarizingEntries(claimed).forEach(entry -> entry.failSummaryBySystemError(reason));
    }

    private boolean lockAndCloseIfRevoked(ClaimedSummaryGroup claimed) {
        SummaryGroup group = claimed.group();
        summaryResultRepository.findGroupForUpdate(group.childId(), group.entryDate(), group.institutionId());
        childRepository.findByIdForUpdate(group.childId());
        return closeIfConsentRevoked(claimed);
    }

    private SummaryResult saveNoContent(SummaryGroup group, SummaryResult latest, List<JournalEntry> entries,
            SummaryAgentReply reply) {
        String uncovered = entries.stream().map(JournalEntry::getId).sorted().map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
        return saveRevision(latest,
                pending -> pending.overwriteNoContent(uncovered, reply.rawJson()),
                revision -> SummaryResult.noContent(group.childId(), group.entryDate(), group.institutionId(),
                        revision, uncovered, reply.rawJson()));
    }

    private SummaryResult saveRevision(SummaryResult latest, Consumer<SummaryResult> overwrite,
            IntFunction<SummaryResult> create) {
        if (latest != null && latest.isBeforeApproval()) {
            overwrite.accept(latest);
            return latest;
        }
        int revision = latest == null ? 1 : latest.getRevision() + 1;
        return summaryResultRepository.save(create.apply(revision));
    }

    private static boolean isEmptyContent(SummaryAgentResponse response) {
        return response.content() == null || response.content().isBlank();
    }

    private List<JournalEntry> summarizingEntries(ClaimedSummaryGroup claimed) {
        return journalEntryRepository.findAllById(claimed.journalEntryIds()).stream()
                .filter(entry -> !entry.isDeleted())
                .filter(entry -> entry.getStatus() == JournalEntryStatus.SUMMARIZING)
                .toList();
    }

    private static String toJson(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }
}
