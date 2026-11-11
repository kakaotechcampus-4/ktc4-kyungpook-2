package com.itda.backend.service.insight;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.global.json.JsonColumns;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class InsightEvidenceReader {

    private final SummaryResultRepository summaryResultRepository;
    private final ChildContextRepository childContextRepository;

    public Map<Long, Evidence> read(List<InsightResult> insights) {
        Map<Long, List<Long>> summaryIdsOf = new HashMap<>();
        Map<Long, Set<Long>> lookedUpOf = new HashMap<>();
        Set<Long> allSummaryIds = new HashSet<>();
        for (InsightResult insight : insights) {
            List<Long> ids = summaryIdsIn(insight);
            Set<Long> lookedUp = lookedUpSummaryIdsOf(insight);
            lookedUpOf.put(insight.getId(), lookedUp);
            lookedUp.stream().filter(id -> !ids.contains(id)).forEach(ids::add);
            summaryIdsOf.put(insight.getId(), ids);
            allSummaryIds.addAll(ids);
            allClaimRefs(insight).forEach(ref -> allSummaryIds.add(ref.summaryId()));
        }
        if (allSummaryIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, SummaryResult> summaries = summaryResultRepository.findAllById(allSummaryIds).stream()
                .collect(Collectors.toMap(SummaryResult::getId, s -> s));
        Map<Long, Long> contextIdOf = childContextRepository.findBySummaryResultIdIn(allSummaryIds).stream()
                .collect(Collectors.toMap(ChildContext::getSummaryResultId, ChildContext::getId));

        Map<Long, Evidence> evidences = new HashMap<>();
        for (InsightResult insight : insights) {
            Set<Long> lookedUp = lookedUpOf.get(insight.getId());
            List<Ref> refs = new ArrayList<>();
            for (Long summaryId : summaryIdsOf.getOrDefault(insight.getId(), List.of())) {
                SummaryResult summary = summaries.get(summaryId);
                Long contextId = contextIdOf.get(summaryId);
                if (summary == null || contextId == null) {
                    continue;
                }
                if (!summary.getChildId().equals(insight.getChildId())) {
                    log.warn("claim_id points at another child's summary, dropped insightId={} summaryId={}",
                            insight.getId(), summaryId);
                    continue;
                }
                boolean past = lookedUp.contains(summaryId) && summary.getEntryDate().isBefore(insight.getPeriodFrom());
                refs.add(new Ref(contextId, summary.getEntryDate(), summary.getInstitutionId(), past,
                        sentencesOf(insight, summary)));
            }
            evidences.put(insight.getId(), new Evidence(refs, hasTeacherClaim(insight, summaries)));
        }
        return evidences;
    }

    private static List<Sentence> sentencesOf(InsightResult insight, SummaryResult summary) {
        JsonNode claims = JsonColumns.readArray(summary.getClaims(), "summary_result.claims", summary.getId());

        Map<Integer, String> roleOf = new LinkedHashMap<>();
        for (JsonNode node : JsonColumns.readArray(insight.getClaimIds(),
                "insight_result.claim_ids", insight.getId())) {
            ClaimRef ref = ClaimRef.parse(node.asText());
            if (ref != ClaimRef.UNKNOWN && ref.summaryId().equals(summary.getId())) {
                roleOf.putIfAbsent(ref.order(), Sentence.SITUATION);
            }
        }
        for (JsonNode support : JsonColumns.readArray(insight.getSupports(),
                "insight_result.supports", insight.getId())) {
            for (JsonNode node : support.path("claim_ids")) {
                ClaimRef ref = ClaimRef.parse(node.asText());
                if (ref != ClaimRef.UNKNOWN && ref.summaryId().equals(summary.getId())) {
                    roleOf.put(ref.order(), Sentence.SUPPORT);
                }
            }
        }

        List<Sentence> sentences = new ArrayList<>();
        roleOf.forEach((order, role) -> {
            String text = claims.path(order).path("text").asText("");
            if (!text.isBlank()) {
                sentences.add(new Sentence(text, role));
            }
        });
        return sentences;
    }

    private static boolean hasTeacherClaim(InsightResult insight, Map<Long, SummaryResult> summaries) {
        List<ClaimRef> refs = allClaimRefs(insight);
        if (refs.isEmpty()) {
            return true;
        }
        for (ClaimRef ref : refs) {
            SummaryResult summary = summaries.get(ref.summaryId());
            if (summary == null || !summary.getChildId().equals(insight.getChildId())) {
                return true;
            }
            JsonNode claim = JsonColumns.readArray(summary.getClaims(), "summary_result.claims", summary.getId())
                    .path(ref.order());
            JsonNode source = claim.path("source");
            boolean ai = source.isMissingNode() || source.isNull() || "AI".equals(source.asText());
            if (claim.isMissingNode() || !ai) {
                return true;
            }
        }
        return false;
    }

    private static List<ClaimRef> allClaimRefs(InsightResult insight) {
        List<ClaimRef> refs = new ArrayList<>();
        JsonColumns.readArray(insight.getClaimIds(), "insight_result.claim_ids", insight.getId())
                .forEach(node -> refs.add(ClaimRef.parse(node.asText())));
        for (JsonNode support : JsonColumns.readArray(insight.getSupports(),
                "insight_result.supports", insight.getId())) {
            JsonNode claimIds = support.path("claim_ids");
            if (!claimIds.isArray() || claimIds.isEmpty()) {
                refs.add(ClaimRef.UNKNOWN);
                continue;
            }
            claimIds.forEach(node -> refs.add(ClaimRef.parse(node.asText())));
        }
        return refs;
    }

    private static List<Long> summaryIdsIn(InsightResult insight) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : JsonColumns.readArray(insight.getClaimIds(),
                "insight_result.claim_ids", insight.getId())) {
            if (!node.isTextual()) {
                continue;
            }
            ClaimRef ref = ClaimRef.parse(node.asText());
            if (ref == ClaimRef.UNKNOWN) {
                log.warn("unexpected claim_id format insightId={} claimId={}", insight.getId(), node.asText());
            } else if (!ids.contains(ref.summaryId())) {
                ids.add(ref.summaryId());
            }
        }
        return ids;
    }

    private static Set<Long> lookedUpSummaryIdsOf(InsightResult insight) {
        Set<Long> cited = allClaimRefs(insight).stream().map(ClaimRef::summaryId).collect(Collectors.toSet());
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode node : JsonColumns.readArray(insight.getLookedUpClaimIds(), "insight_result.looked_up_claim_ids",
                insight.getId())) {
            ClaimRef ref = ClaimRef.parse(node.asText());
            if (ref == ClaimRef.UNKNOWN || !cited.contains(ref.summaryId())) {
                log.warn("looked_up_claim_id not cited, ignored insightId={} claimId={}", insight.getId(), node);
                continue;
            }
            ids.add(ref.summaryId());
        }
        return ids;
    }

    private record ClaimRef(Long summaryId, int order) {

        static final ClaimRef UNKNOWN = new ClaimRef(-1L, -1);

        static ClaimRef parse(String claimId) {
            int dash = claimId.indexOf('-');
            if (dash <= 0) {
                return UNKNOWN;
            }
            try {
                return new ClaimRef(Long.valueOf(claimId.substring(0, dash)),
                        Integer.parseInt(claimId.substring(dash + 1)));
            } catch (NumberFormatException e) {
                return UNKNOWN;
            }
        }
    }

    public record Ref(Long childContextId, LocalDate date, Long institutionId, boolean lookedUp,
            List<Sentence> sentences) {
    }

    public record Sentence(String text, String role) {

        public static final String SITUATION = "상황";
        public static final String SUPPORT = "대처";
    }

    public record Evidence(List<Ref> refs, boolean teacherWritten) {

        public static final Evidence EMPTY = new Evidence(List.of(), true);

        public Set<Long> institutionIds() {
            return refs.stream().map(Ref::institutionId).collect(Collectors.toSet());
        }
    }
}
