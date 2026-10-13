package com.itda.backend.service.approval;

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.ReviewFlag;
import com.itda.backend.dto.response.summary.Gate1SummaryResponse.UncoveredEntry;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.repository.validation.ValidationResultRepository;
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

    private final UserService userService;
    private final SummaryResultRepository summaryResultRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final ChildRepository childRepository;
    private final ValidationResultRepository validationResultRepository;

    /** 요청 교사 기관의 그 상태(GATE1_PENDING · HOLD) 요약을 오래된 날짜부터. */
    @Transactional(readOnly = true)
    public List<Gate1SummaryResponse> getSummaries(String userId, SummaryStatus status) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<SummaryResult> summaries =
                summaryResultRepository.findByInstitutionIdAndStatusOrderByEntryDateAscIdAsc(organizationId, status);
        if (summaries.isEmpty()) {
            return List.of();
        }

        Map<Long, String> childNames = childRepository
                .findAllById(summaries.stream().map(SummaryResult::getChildId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Child::getId, Child::getName));

        Map<Long, List<Long>> covered = new HashMap<>();
        Map<Long, List<Long>> uncovered = new HashMap<>();
        Set<Long> entryIds = new HashSet<>();
        for (SummaryResult s : summaries) {
            covered.put(s.getId(), parseIds(s.getCoveredEntryIds(), s.getId()));
            uncovered.put(s.getId(), parseIds(s.getUncoveredEntryIds(), s.getId()));
            entryIds.addAll(covered.get(s.getId()));
            entryIds.addAll(uncovered.get(s.getId()));
        }
        Map<Long, JournalEntry> entries = journalEntryRepository.findAllById(entryIds).stream()
                .filter(e -> !e.isDeleted())
                .collect(Collectors.toMap(JournalEntry::getId, Function.identity()));
        Map<Long, ValidationResult> latestValidation = latestValidationOf(entries.keySet());

        return summaries.stream()
                .map(s -> new Gate1SummaryResponse(
                        s.getId(),
                        s.getChildId(),
                        childNames.get(s.getChildId()),
                        s.getEntryDate(),
                        s.getRevision(),
                        s.getStatus().name(),
                        s.getContent(),
                        s.isNeedsReview(),
                        parseStrings(s.getReviewReasons(), "summary_result.review_reasons", s.getId()),
                        uncoveredEntries(uncovered.get(s.getId()), entries),
                        reviewFlags(s, covered.get(s.getId()), entries, latestValidation)))
                .toList();
    }

    private static List<UncoveredEntry> uncoveredEntries(List<Long> ids, Map<Long, JournalEntry> entries) {
        return ids.stream()
                .map(entries::get)
                .filter(e -> e != null)
                .map(e -> new UncoveredEntry(e.getId(), e.getContent()))
                .toList();
    }

    private List<ReviewFlag> reviewFlags(SummaryResult summary, List<Long> coveredIds,
            Map<Long, JournalEntry> entries, Map<Long, ValidationResult> latestValidation) {
        Map<Long, List<Span>> claimSpans = parseClaimSpans(summary);
        List<ReviewFlag> flags = new ArrayList<>();
        for (Long entryId : coveredIds) {
            ValidationResult validation = latestValidation.get(entryId);
            if (!entries.containsKey(entryId) || validation == null
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
        for (JsonNode node : readArray(raw, "summary_result.entry_ids", summaryId)) {
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
