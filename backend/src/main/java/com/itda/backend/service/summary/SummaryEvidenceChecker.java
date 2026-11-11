package com.itda.backend.service.summary;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.dto.response.summary.SummaryAgentResponse;

@Component
public class SummaryEvidenceChecker {

    public Optional<String> findProblem(SummaryGroup group, Set<Long> sentIds, Map<Long, String> sources,
            SummaryAgentResponse response) {
        if (!group.childId().equals(response.childId())
                || !group.entryDate().toString().equals(response.entryDate())
                || !group.institutionId().equals(response.institutionId())) {
            return Optional.of("response is for another group childId=" + response.childId()
                    + " entryDate=" + response.entryDate() + " institutionId=" + response.institutionId());
        }
        Optional<String> listed = checkEntryIds("covered_entry_ids", response.coveredEntryIds(), sentIds)
                .or(() -> checkEntryIds("uncovered_entry_ids", response.uncoveredEntryIds(), sentIds));
        if (listed.isPresent()) {
            return listed;
        }

        JsonNode claims = response.claims();
        if (response.isNoContent()) {
            boolean hasClaims = isNonEmptyArray(claims);
            boolean hasContent = response.content() != null && !response.content().isBlank();
            boolean hasCovered = isNonEmptyArray(response.coveredEntryIds());
            return hasClaims || hasContent || hasCovered
                    ? Optional.of("no_content with claims=" + hasClaims + " content=" + hasContent
                            + " covered=" + hasCovered)
                    : Optional.empty();
        }
        if (claims == null || !claims.isArray() || claims.isEmpty()) {
            return Optional.of("no claims");
        }
        for (int i = 0; i < claims.size(); i++) {
            JsonNode evidence = claims.get(i).get("evidence");
            if (evidence == null || !evidence.isArray() || evidence.isEmpty()) {
                return Optional.of("claim[" + i + "]: no evidence");
            }
            for (int j = 0; j < evidence.size(); j++) {
                Optional<String> problem = checkEvidence(evidence.get(j), sources);
                if (problem.isPresent()) {
                    return Optional.of("claim[" + i + "].evidence[" + j + "]: " + problem.get());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isNonEmptyArray(JsonNode node) {
        return node != null && node.isArray() && !node.isEmpty();
    }

    private Optional<String> checkEntryIds(String field, JsonNode ids, Set<Long> sentIds) {
        if (ids == null || ids.isNull()) {
            return Optional.empty();
        }
        for (JsonNode id : ids) {
            if (!sentIds.contains(id.asLong())) {
                return Optional.of(field + " has entry not in group journalEntryId=" + id.asLong());
            }
        }
        return Optional.empty();
    }

    private Optional<String> checkEvidence(JsonNode evidence, Map<Long, String> sources) {
        long journalEntryId = evidence.path("journal_entry_id").asLong(-1);
        String content = sources.get(journalEntryId);
        if (content == null) {
            return Optional.of("journal entry not in group journalEntryId=" + journalEntryId);
        }
        JsonNode span = evidence.get("span");
        if (span == null || !span.path("start").isInt() || !span.path("end").isInt()) {
            return Optional.of("no span journalEntryId=" + journalEntryId);
        }
        int start = span.get("start").asInt();
        int end = span.get("end").asInt();
        int length = content.codePointCount(0, content.length());
        if (start < 0 || start >= end || end > length) {
            return Optional.of("span out of range journalEntryId=" + journalEntryId
                    + " start=" + start + " end=" + end + " length=" + length);
        }
        String sliced = content.substring(content.offsetByCodePoints(0, start), content.offsetByCodePoints(0, end));
        if (!sliced.equals(evidence.path("quote").asText(null))) {
            return Optional.of("quote does not match span journalEntryId=" + journalEntryId
                    + " start=" + start + " end=" + end);
        }
        return Optional.empty();
    }
}
