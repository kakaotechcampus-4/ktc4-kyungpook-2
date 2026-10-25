package com.itda.backend.service.approval;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.ClaimCard;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.ReviewFlag;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.UncoveredEntry;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.GroupKey;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.LatestRevision;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.repository.validation.ValidationResultRepository;
import com.itda.backend.service.summary.SummaryRunRequests;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gate 1 검토 목록과 보류함. 요약 본문과 함께 교사가 승인 전에 봐야 할 것을 모아 준다.
 *
 * <p>요약에 들어간 원본의 REVIEW 사유는 그 일지의 검증 REVIEW 구간이 요약 문장(claim) 근거 구간과 겹칠 때만 보여준다 —
 * 요약에 안 실린 부분의 문제까지 띄우면 교사가 요약에 없는 문제를 찾는다. 검증의 사유(issue_types)와 구간(evidence)은
 * 짝이 지어져 있지 않아 일지 단위로 판단하고, 겹치면 그 일지의 사유를 모두 보여준다. 구간이 비어 있으면 어디가 문제인지
 * 몰라 보여준다 (기획 D7). 사유 단위로 거르는 것은 검증 {@code issues} 필드가 생긴 뒤 #179 에서 한다.
 *
 * <p>AI 가 쓴 JSON 이 깨져 있어도 한 건 때문에 목록 전체가 500 나지 않게 빈 값으로 읽는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryGate1QueryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 아직 아동을 모르는 일지. 이 아이 일지일 수 있어 기관·날짜로 본다. */
    private static final Set<JournalEntryStatus> CHILD_UNKNOWN =
            Set.of(JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING);

    /** 아동이 정해졌지만 아직 요약에 반영되지 않은 일지. 그 아이 묶음만 본다. [요약]을 눌러야 요약된다. */
    private static final Set<JournalEntryStatus> NOT_YET_SUMMARIZED = Set.of(JournalEntryStatus.MATCHED,
            JournalEntryStatus.VALIDATING, JournalEntryStatus.VALIDATED);

    private final UserService userService;
    private final SummaryResultRepository summaryResultRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final ChildRepository childRepository;
    private final ValidationResultRepository validationResultRepository;
    private final SummaryRunRequests runRequests;

    /** 요청 교사 기관의 검토 대기(pending) 또는 보류함(hold) 요약을 오래된 날짜부터. */
    @Transactional(readOnly = true)
    public List<Gate1SummaryResponse> getSummaries(String userId, String gate1Status) {
        SummaryStatus status = toSummaryStatus(gate1Status);
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<SummaryResult> summaries =
                summaryResultRepository.findGate1List(organizationId, status);
        if (summaries.isEmpty()) {
            return List.of();
        }

        Map<Long, String> childNames = childRepository
                .findAllById(summaries.stream().map(SummaryResult::getChildId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Child::getId, Child::getName));

        // 요약에 든 일지는 AI 가 보낸 목록이 아니라 그 요약을 가리키는 일지로 정한다. 승인하면 이 일지가 모두 끝나므로,
        // AI 가 어느 목록에도 넣지 않은 일지도 교사가 봐야 한다.
        Map<Long, List<JournalEntry>> entriesBySummary = journalEntryRepository
                .findLiveBySummaryIds(summaries.stream().map(SummaryResult::getId).toList())
                .stream().collect(Collectors.groupingBy(JournalEntry::getSummaryId));
        Map<Long, Set<Long>> covered = new HashMap<>();
        Set<Long> coveredIds = new HashSet<>();
        for (SummaryResult s : summaries) {
            Set<Long> ids = new HashSet<>(parseIds(s.getCoveredEntryIds(), s.getId()));
            ids.retainAll(entriesBySummary.getOrDefault(s.getId(), List.of()).stream()
                    .map(JournalEntry::getId).toList());
            covered.put(s.getId(), ids);
            coveredIds.addAll(ids);
        }
        Map<Long, ValidationResult> latestValidation = latestValidationOf(coveredIds);
        Set<Long> processing = processingSummaryIds(organizationId, summaries);

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
                        s.isNeedsReview(),
                        parseStrings(s.getReviewReasons(), "summary_result.review_reasons", s.getId()),
                        uncoveredEntries(entriesBySummary.getOrDefault(s.getId(), List.of()), covered.get(s.getId())),
                        reviewFlags(s, entriesBySummary.getOrDefault(s.getId(), List.of()), covered.get(s.getId()),
                                latestValidation)))
                .toList();
    }

    /**
     * 곧 바뀌는 요약들. 그 묶음을 지금 요약 중이거나, 교사가 [요약]을 눌러 뒀고 아직 요약에 반영되지 않은 일지가
     * 있는 경우다. 누르지 않았으면 일지가 기다려도 요약은 바뀌지 않는다 — 자동 요약이 없다 (#181). 아동이 정해진
     * 일지(매칭 확정 뒤 검증·요약 대기)는 그 아이 묶음만 본다. 매칭 전·매칭 중 일지는 누구 것인지 몰라 기관·날짜로
     * 본다 — 다른 아이 일지 때문에 그날 모든 요약을 막지 않으려는 것이다. 워커는 최신 판이 대기일 때만 덮어쓰므로,
     * 보류된 판이나 옛 판은 바뀌지 않는다.
     *
     * <p>요약마다 따로 묻지 않고 목록의 아동·날짜로 몇 번만 물어 코드에서 맞춘다 — 요약 수와 상관없이 SQL 수가 같다.
     */
    private Set<Long> processingSummaryIds(Long organizationId, List<SummaryResult> summaries) {
        List<SummaryResult> pending = summaries.stream()
                .filter(s -> s.getStatus() == SummaryStatus.GATE1_PENDING)
                .toList();
        if (pending.isEmpty()) {
            return Set.of();
        }
        Set<Long> childIds = pending.stream().map(SummaryResult::getChildId).collect(Collectors.toSet());
        Set<LocalDate> dates = pending.stream().map(SummaryResult::getEntryDate).collect(Collectors.toSet());
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
        for (SummaryResult s : pending) {
            GroupKey group = new GroupKey(s.getChildId(), s.getEntryDate());
            boolean latest = s.getRevision().equals(latestRevision.get(group));
            boolean willSummarize = requestedGroups.contains(group)
                    && (busyDates.contains(s.getEntryDate()) || waitingGroups.contains(group));
            if (latest && (summarizingGroups.contains(group) || willSummarize)) {
                processing.add(s.getId());
            }
        }
        return processing;
    }

    private static SummaryStatus toSummaryStatus(String gate1Status) {
        return switch (gate1Status) {
            case "pending" -> SummaryStatus.GATE1_PENDING;
            case "hold" -> SummaryStatus.HOLD;
            // 다른 값을 조용히 대기 목록으로 처리하면 화면이 엉뚱한 목록을 보여준다.
            default -> throw new SummaryException(SummaryErrorCode.INVALID_REQUEST,
                    "unsupported gate1Status: " + gate1Status);
        };
    }

    /**
     * 문장 카드. 번호가 결정 요청의 index 라, 글이 없는 문장도 빼지 않고 빈 글로 자리를 지킨다. 결정과 같은 기준으로
     * 읽는다 ({@link Gate1Cards#readClaims}) — 목록에 보인 카드를 그대로 보냈는데 400 이 나면 안 된다.
     */
    private static List<ClaimCard> claimCards(SummaryResult summary) {
        JsonNode claims = Gate1Cards.readClaims(summary.getClaims());
        List<ClaimCard> cards = new ArrayList<>();
        for (int index = 0; index < claims.size(); index++) {
            cards.add(new ClaimCard(index, claims.get(index).path("text").asText("")));
        }
        return cards;
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
                flags.add(new ReviewFlag(entryId,
                        parseStrings(validation.getIssueTypes(), "validation_result.issue_types", validation.getId())));
            }
        }
        return flags;
    }

    private Map<Long, ValidationResult> latestValidationOf(Set<Long> entryIds) {
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        // 최신순으로 오므로 일지마다 처음 본 것이 최신이다.
        Map<Long, ValidationResult> latest = new LinkedHashMap<>();
        validationResultRepository.findByJournalEntryIdInOrderByIdDesc(entryIds)
                .forEach(v -> latest.putIfAbsent(v.getJournalEntryId(), v));
        return latest;
    }

    /** 일지 원문 기준 코드포인트 구간 [start, end). 검증 evidence 와 요약 claim 근거가 같은 기준이다. */
    private record Span(int start, int end) {

        boolean overlaps(Span other) {
            return start < other.end && other.start < end;
        }
    }

    /** claims: [{text, evidence: [{journal_entry_id, quote, span: {start, end}}]}] → 일지별 근거 구간. */
    private static Map<Long, List<Span>> parseClaimSpans(SummaryResult summary) {
        Map<Long, List<Span>> spans = new HashMap<>();
        for (JsonNode claim : readArray(summary.getClaims(), "summary_result.claims", summary.getId())) {
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

    /** evidence: [{start, end}]. 못 읽은 구간은 버린다 — 모두 못 읽으면 비어 있는 것으로 보고 사유를 보여준다. */
    private static List<Span> parseValidationSpans(ValidationResult validation) {
        List<Span> spans = new ArrayList<>();
        for (JsonNode node : readArray(validation.getEvidence(), "validation_result.evidence", validation.getId())) {
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

    private static List<Long> parseIds(String raw, Long summaryId) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : readArray(raw, "summary_result.covered_entry_ids", summaryId)) {
            if (node.canConvertToLong()) {
                ids.add(node.asLong());
            }
        }
        return ids;
    }

    private static List<String> parseStrings(String raw, String column, Long rowId) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : readArray(raw, column, rowId)) {
            if (node.isTextual()) {
                values.add(node.asText());
            }
        }
        return values;
    }

    private static JsonNode readArray(String raw, String column, Long rowId) {
        if (raw == null || raw.isBlank()) {
            return MAPPER.createArrayNode();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node.isArray() ? node : MAPPER.createArrayNode();
        } catch (JsonProcessingException e) {
            log.warn("failed to parse {} id={}", column, rowId, e);
            return MAPPER.createArrayNode();
        }
    }
}
