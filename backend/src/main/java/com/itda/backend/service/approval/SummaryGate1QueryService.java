package com.itda.backend.service.approval;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.ClaimCard;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.ClaimEvidence;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.ReviewFlag;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.SourceEntry;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.UncoveredEntry;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.GroupKey;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.Gate1Key;
import com.itda.backend.repository.summary.LatestRevision;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.repository.validation.ValidationResultRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.summary.SummaryProperties;
import com.itda.backend.service.summary.SummaryRunRequests;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.validation.ValidationReasons;
import com.itda.backend.global.json.JsonColumns;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryGate1QueryService {

    private static final Set<JournalEntryStatus> CHILD_UNKNOWN =
            Set.of(JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING);

    private static final Set<JournalEntryStatus> NOT_YET_SUMMARIZED = Set.of(JournalEntryStatus.MATCHED,
            JournalEntryStatus.VALIDATING, JournalEntryStatus.VALIDATED);

    private final UserService userService;
    private final SummaryResultRepository summaryResultRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final ChildRepository childRepository;
    private final ValidationResultRepository validationResultRepository;
    private final SummaryRunRequests runRequests;
    private final ConsentStateReader consentStateReader;
    private final SummaryProperties summaryProperties;

    @Transactional(readOnly = true)
    public List<Gate1SummaryResponse> getSummaries(String userId, String gate1Status) {
        SummaryStatus status = toSummaryStatus(gate1Status);
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<SummaryResult> summaries = withConsent(organizationId,
                summaryResultRepository.findGate1List(organizationId, status), SummaryResult::getChildId);
        if (summaries.isEmpty()) {
            return List.of();
        }

        Map<Long, String> childNames = childRepository
                .findAllById(summaries.stream().map(SummaryResult::getChildId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Child::getId, Child::getName));

        Map<Long, List<JournalEntry>> entriesBySummary = journalEntryRepository
                .findLiveBySummaryIds(summaries.stream().map(SummaryResult::getId).toList())
                .stream().collect(Collectors.groupingBy(JournalEntry::getSummaryId));
        Map<Long, Set<Long>> covered = new HashMap<>();
        Set<Long> coveredIds = new HashSet<>();
        for (SummaryResult s : summaries) {
            Set<Long> ids = new HashSet<>(JsonColumns.readLongs(s.getCoveredEntryIds(),
                    "summary_result.covered_entry_ids", s.getId()));
            ids.retainAll(entriesBySummary.getOrDefault(s.getId(), List.of()).stream()
                    .map(JournalEntry::getId).toList());
            covered.put(s.getId(), ids);
            coveredIds.addAll(ids);
        }
        Map<Long, ValidationResult> latestValidation = latestValidationOf(coveredIds);
        Set<Long> processing = processingSummaryIds(organizationId, summaries.stream().map(Gate1Key::of).toList());

        return summaries.stream()
                .map(s -> new Gate1SummaryResponse(
                        s.getId(),
                        s.getChildId(),
                        childNames.get(s.getChildId()),
                        s.getEntryDate(),
                        s.getRevision(),
                        s.getStatus().name(),
                        Gate1Version.of(s, entriesBySummary.getOrDefault(s.getId(), List.of())),
                        processing.contains(s.getId()),
                        s.getContent(),
                        claimCards(s),
                        s.isNoContent(),
                        s.isNeedsReview(),
                        JsonColumns.readTexts(s.getReviewReasons(), "summary_result.review_reasons", s.getId()),
                        uncoveredEntries(entriesBySummary.getOrDefault(s.getId(), List.of()), covered.get(s.getId())),
                        reviewFlags(s, entriesBySummary.getOrDefault(s.getId(), List.of()), covered.get(s.getId()),
                                latestValidation),
                        sourceEntries(entriesBySummary.getOrDefault(s.getId(), List.of()))))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Gate1BoardItem> getBoardItems(Long organizationId) {
        List<Gate1Key> keys = withConsent(organizationId, summaryResultRepository.findGate1Keys(organizationId,
                List.of(SummaryStatus.GATE1_PENDING, SummaryStatus.HOLD)), Gate1Key::childId);
        if (keys.isEmpty()) {
            return List.of();
        }
        Map<Long, String> childNames = childRepository
                .findAllById(keys.stream().map(Gate1Key::childId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Child::getId, Child::getName));
        Set<Long> processing = processingSummaryIds(organizationId, keys);
        return keys.stream()
                .map(k -> new Gate1BoardItem(k.id(), k.childId(), childNames.get(k.childId()), k.entryDate(),
                        k.status(), processing.contains(k.id()), k.noContent()))
                .toList();
    }

    private <T> List<T> withConsent(Long organizationId, List<T> summaries, Function<T, Long> childIdOf) {
        if (summaries.isEmpty()) {
            return summaries;
        }
        Set<Long> granted = consentStateReader.grantedChildIds(
                summaries.stream().map(childIdOf).collect(Collectors.toSet()), organizationId);
        return summaries.stream().filter(s -> granted.contains(childIdOf.apply(s))).toList();
    }

    private Set<Long> processingSummaryIds(Long organizationId, List<Gate1Key> summaries) {
        List<Gate1Key> pending = summaries.stream()
                .filter(s -> s.status() == SummaryStatus.GATE1_PENDING)
                .toList();
        if (pending.isEmpty()) {
            return Set.of();
        }
        Set<Long> childIds = pending.stream().map(Gate1Key::childId).collect(Collectors.toSet());
        Set<LocalDate> dates = pending.stream().map(Gate1Key::entryDate).collect(Collectors.toSet());
        String institutionId = String.valueOf(organizationId);

        Map<GroupKey, Integer> latestRevision = summaryResultRepository
                .findLatestRevisions(organizationId, childIds, dates).stream()
                .collect(Collectors.toMap(r -> new GroupKey(r.childId(), r.entryDate()), LatestRevision::revision));
        Set<LocalDate> busyDates = new HashSet<>(
                journalEntryRepository.findInProgressDates(CHILD_UNKNOWN, institutionId, dates));
        Set<GroupKey> waitingGroups = new HashSet<>(
                journalEntryRepository.findGroupKeys(NOT_YET_SUMMARIZED, institutionId, dates));
        Set<GroupKey> summarizingGroups = new HashSet<>(
                journalEntryRepository.findGroupKeys(Set.of(JournalEntryStatus.SUMMARIZING), institutionId, dates));
        Set<GroupKey> requestedGroups = runRequests.pendingGroupsOf(organizationId, dates).stream()
                .map(g -> new GroupKey(g.childId(), g.entryDate()))
                .collect(Collectors.toSet());

        Set<Long> processing = new HashSet<>();
        for (Gate1Key s : pending) {
            GroupKey group = new GroupKey(s.childId(), s.entryDate());
            boolean latest = s.revision().equals(latestRevision.get(group));
            boolean willSummarize = (summaryProperties.autoRun() || requestedGroups.contains(group))
                    && (busyDates.contains(s.entryDate()) || waitingGroups.contains(group));
            if (latest && (summarizingGroups.contains(group) || willSummarize)) {
                processing.add(s.id());
            }
        }
        return processing;
    }

    private static SummaryStatus toSummaryStatus(String gate1Status) {
        return switch (gate1Status) {
            case "pending" -> SummaryStatus.GATE1_PENDING;
            case "hold" -> SummaryStatus.HOLD;
            default -> throw new SummaryException(SummaryErrorCode.INVALID_REQUEST,
                    "unsupported gate1Status: " + gate1Status);
        };
    }

    private static List<ClaimCard> claimCards(SummaryResult summary) {
        JsonNode claims = Gate1Cards.readClaims(summary);
        List<ClaimCard> cards = new ArrayList<>();
        for (int index = 0; index < claims.size(); index++) {
            JsonNode claim = claims.get(index);
            cards.add(new ClaimCard(index, claim.path("text").asText(""), claimEvidence(claim)));
        }
        return cards;
    }

    private static List<ClaimEvidence> claimEvidence(JsonNode claim) {
        String source = claim.path("source").asText("AI");
        if (!"AI".equals(source)) {
            return List.of();
        }
        List<ClaimEvidence> evidence = new ArrayList<>();
        for (JsonNode item : claim.path("evidence")) {
            JsonNode entryId = item.path("journal_entry_id");
            if (entryId.canConvertToLong()) {
                evidence.add(new ClaimEvidence(entryId.asLong(), item.path("quote").asText("")));
            }
        }
        return evidence;
    }

    private static List<SourceEntry> sourceEntries(List<JournalEntry> entries) {
        return entries.stream().map(e -> new SourceEntry(e.getId(), e.getContent())).toList();
    }

    private static List<UncoveredEntry> uncoveredEntries(List<JournalEntry> entries, Set<Long> coveredIds) {
        return entries.stream()
                .filter(e -> !coveredIds.contains(e.getId()))
                .map(e -> new UncoveredEntry(e.getId(), e.getContent()))
                .toList();
    }

    private List<ReviewFlag> reviewFlags(SummaryResult summary, List<JournalEntry> entries, Set<Long> coveredIds,
            Map<Long, ValidationResult> latestValidation) {
        Map<Long, List<Span>> claimSpans = parseClaimSpans(summary);
        Map<Long, List<CardSpan>> cardSpans = cardSpans(summary);
        List<ReviewFlag> flags = new ArrayList<>();
        for (JournalEntry entry : entries) {
            Long entryId = entry.getId();
            ValidationResult validation = latestValidation.get(entryId);
            if (!coveredIds.contains(entryId) || validation == null
                    || validation.getVerdict() != ValidationVerdict.REVIEW) {
                continue;
            }
            List<Span> flagged = parseValidationSpans(validation);
            List<Span> cited = claimSpans.getOrDefault(entryId, List.of());
            if (flagged.isEmpty() || flagged.stream().anyMatch(f -> cited.stream().anyMatch(f::overlaps))) {
                List<String> issueTypes =
                        JsonColumns.readTexts(validation.getIssueTypes(),
                                "validation_result.issue_types", validation.getId());
                List<Integer> claimIndexes = cardSpans.getOrDefault(entryId, List.of()).stream()
                        .filter(card -> flagged.stream().anyMatch(card.span()::overlaps))
                        .map(CardSpan::index)
                        .distinct()
                        .sorted()
                        .toList();
                flags.add(new ReviewFlag(entryId, issueTypes, ValidationReasons.sentencesOf(issueTypes),
                        claimIndexes));
            }
        }
        return flags;
    }

    private Map<Long, ValidationResult> latestValidationOf(Set<Long> entryIds) {
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ValidationResult> latest = new LinkedHashMap<>();
        validationResultRepository.findByJournalEntryIdInOrderByIdDesc(entryIds)
                .forEach(v -> latest.putIfAbsent(v.getJournalEntryId(), v));
        return latest;
    }

    private record Span(int start, int end) {

        boolean overlaps(Span other) {
            return start < other.end && other.start < end;
        }
    }

    private static Map<Long, List<Span>> parseClaimSpans(SummaryResult summary) {
        Map<Long, List<Span>> spans = new HashMap<>();
        for (JsonNode claim : JsonColumns.readArray(summary.getClaims(), "summary_result.claims", summary.getId())) {
            for (JsonNode evidence : claim.path("evidence")) {
                JsonNode entryId = evidence.path("journal_entry_id");
                Span span = toSpan(evidence.path("span"));
                if (entryId.canConvertToLong() && span != null) {
                    spans.computeIfAbsent(entryId.asLong(), k -> new ArrayList<>()).add(span);
                }
            }
        }
        return spans;
    }

    private static Map<Long, List<CardSpan>> cardSpans(SummaryResult summary) {
        JsonNode claims = Gate1Cards.readClaims(summary);
        Map<Long, List<CardSpan>> spans = new HashMap<>();
        for (int index = 0; index < claims.size(); index++) {
            JsonNode claim = claims.get(index);
            if (!"AI".equals(claim.path("source").asText("AI"))) {
                continue;
            }
            for (JsonNode evidence : claim.path("evidence")) {
                JsonNode entryId = evidence.path("journal_entry_id");
                Span span = toSpan(evidence.path("span"));
                if (entryId.canConvertToLong() && span != null) {
                    spans.computeIfAbsent(entryId.asLong(), k -> new ArrayList<>()).add(new CardSpan(index, span));
                }
            }
        }
        return spans;
    }

    private record CardSpan(int index, Span span) {
    }

    private static List<Span> parseValidationSpans(ValidationResult validation) {
        List<Span> spans = new ArrayList<>();
        for (JsonNode node : JsonColumns.readArray(validation.getEvidence(),
                "validation_result.evidence", validation.getId())) {
            Span span = toSpan(node);
            if (span != null) {
                spans.add(span);
            }
        }
        return spans;
    }

    private static Span toSpan(JsonNode node) {
        JsonNode start = node.path("start");
        JsonNode end = node.path("end");
        if (!start.isInt() || !end.isInt()) {
            return null;
        }
        return new Span(start.asInt(), end.asInt());
    }
}
