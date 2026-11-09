package com.itda.backend.service.insight;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.ApprovalTargetType;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.request.insight.InsightAgentRequest;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.global.json.JsonColumns;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightService {

    static final int HISTORY_WEEKS = 12;
    static final int HISTORY_LIMIT = 200;

    private final ChildContextRepository childContextRepository;
    private final SummaryResultRepository summaryResultRepository;
    private final ApprovalRepository approvalRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final OrganizationRepository organizationRepository;
    private final ChildRepository childRepository;
    private final ConsentStateReader consentStateReader;

    @Transactional(readOnly = true)
    public Optional<PreparedInsight> prepare(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        Child child = childRepository.findByIdAndDeletedAtIsNull(childId).orElse(null);
        if (child == null) {
            log.warn("child not found, insight skipped childId={}", childId);
            return Optional.empty();
        }

        List<InsightAgentRequest.Institution> consented = consentedInstitutionsOf(childId);
        if (consented.isEmpty()) {
            return Optional.empty();
        }
        Set<Long> consentedIds = consented.stream()
                .map(InsightAgentRequest.Institution::institutionId)
                .collect(Collectors.toSet());

        ApprovedMaterial material = approvedSummariesIn(childId, periodFrom, periodTo);
        List<SummaryResult> approved = material.summaries();
        if (approved.isEmpty()) {
            return Optional.empty();
        }

        List<InsightAgentRequest.Claim> claims = consentedClaimsOf(approved, consentedIds);
        if (claims.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new PreparedInsight(
                new InsightAgentRequest(childId, child.getName(), periodFrom.toString(), periodTo.toString(),
                        claims, consented, historyClaimsOf(childId, periodFrom, consentedIds)),
                material.lastContextId()));
    }

    private List<InsightAgentRequest.Claim> historyClaimsOf(Long childId, LocalDate periodFrom, Set<Long> consentedIds) {
        List<SummaryResult> approved = approvedSummariesIn(
                childId, periodFrom.minusWeeks(HISTORY_WEEKS), periodFrom.minusDays(1)).summaries();
        List<InsightAgentRequest.Claim> history = consentedClaimsOf(approved, consentedIds);
        if (history.size() <= HISTORY_LIMIT) {
            return history;
        }
        int start = history.size() - HISTORY_LIMIT;
        String cutSummary = summaryPrefixOf(history.get(start));
        while (start < history.size() && start > 0
                && summaryPrefixOf(history.get(start - 1)).equals(cutSummary)
                && summaryPrefixOf(history.get(start)).equals(cutSummary)) {
            start++;
        }
        return history.subList(start, history.size());
    }

    private static String summaryPrefixOf(InsightAgentRequest.Claim claim) {
        return claim.claimId().substring(0, claim.claimId().indexOf('-'));
    }

    private List<InsightAgentRequest.Claim> consentedClaimsOf(List<SummaryResult> summaries, Set<Long> consentedIds) {
        Map<Long, Organization> institutions = institutionsOf(summaries);
        List<InsightAgentRequest.Claim> claims = new ArrayList<>();
        for (SummaryResult summary : summaries) {
            if (!consentedIds.contains(summary.getInstitutionId())) {
                continue;
            }
            Organization institution = institutions.get(summary.getInstitutionId());
            if (institution == null) {
                log.warn("institution not found, summary skipped summaryId={} institutionId={}",
                        summary.getId(), summary.getInstitutionId());
                continue;
            }
            claims.addAll(claimsOf(summary, institution));
        }
        return claims;
    }

    private record ApprovedMaterial(List<SummaryResult> summaries, Long lastContextId) {
        static ApprovedMaterial empty() {
            return new ApprovedMaterial(List.of(), null);
        }
    }

    private ApprovedMaterial approvedSummariesIn(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        List<SummaryResult> inPeriod =
                summaryResultRepository.findByChildIdAndEntryDateBetween(childId, periodFrom, periodTo);
        if (inPeriod.isEmpty()) {
            return ApprovedMaterial.empty();
        }

        List<Long> summaryIds = inPeriod.stream().map(SummaryResult::getId).toList();
        List<ChildContext> contexts = childContextRepository.findBySummaryResultIdIn(summaryIds);
        if (contexts.isEmpty()) {
            return ApprovedMaterial.empty();
        }
        Set<Long> approvedIds = contexts.stream()
                .map(ChildContext::getSummaryResultId)
                .collect(Collectors.toSet());
        Long lastContextId = contexts.stream().map(ChildContext::getId).max(Long::compareTo).orElseThrow();

        List<SummaryResult> approved = inPeriod.stream()
                .filter(summary -> approvedIds.contains(summary.getId()))
                .toList();
        Set<Long> legacyCorrected = legacyCorrectedAmong(approved);
        List<SummaryResult> summaries = approved.stream()
                .filter(summary -> !legacyCorrected.contains(summary.getId()))
                .sorted(Comparator.comparing(SummaryResult::getEntryDate).thenComparing(SummaryResult::getId))
                .toList();
        return new ApprovedMaterial(summaries, lastContextId);
    }

    private Set<Long> legacyCorrectedAmong(List<SummaryResult> summaries) {
        Set<Long> corrected = approvalRepository
                .findByTargetTypeAndDecisionAndTargetIdIn(ApprovalTargetType.SUMMARY, ApprovalDecision.CORRECTED,
                        summaries.stream().map(SummaryResult::getId).toList())
                .stream()
                .map(Approval::getTargetId)
                .collect(Collectors.toSet());
        return summaries.stream()
                .filter(summary -> corrected.contains(summary.getId()))
                .filter(summary -> !hasSource(readClaims(summary)))
                .map(SummaryResult::getId)
                .collect(Collectors.toSet());
    }

    private static boolean hasSource(JsonNode claims) {
        if (claims == null || !claims.isArray()) {
            return false;
        }
        for (JsonNode claim : claims) {
            if (claim.hasNonNull("source")) {
                return true;
            }
        }
        return false;
    }

    private Map<Long, Organization> institutionsOf(List<SummaryResult> summaries) {
        List<Long> ids = summaries.stream().map(SummaryResult::getInstitutionId).distinct().toList();
        return organizationRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Organization::getId, Function.identity()));
    }

    private List<InsightAgentRequest.Institution> consentedInstitutionsOf(Long childId) {
        Set<Long> enrolled = childOrganizationRepository.findByChildIdAndDeletedAtIsNull(childId).stream()
                .map(ChildOrganization::getOrganizationId)
                .collect(Collectors.toSet());
        List<Long> organizationIds = consentStateReader.insightStatesOf(List.of(childId))
                .getOrDefault(childId, Map.of()).entrySet().stream()
                .filter(entry -> entry.getValue() == ConsentState.GRANTED)
                .map(Map.Entry::getKey)
                .filter(enrolled::contains)
                .distinct()
                .toList();
        return organizationRepository.findAllById(organizationIds).stream()
                .map(organization -> new InsightAgentRequest.Institution(
                        organization.getId(), InstitutionTypeNames.of(organization.getType())))
                .toList();
    }

    private List<InsightAgentRequest.Claim> claimsOf(SummaryResult summary, Organization institution) {
        JsonNode claims = readClaims(summary);
        if (claims == null || !claims.isArray()) {
            return List.of();
        }

        String institutionType = InstitutionTypeNames.of(institution.getType());
        String entryDate = summary.getEntryDate().toString();
        List<InsightAgentRequest.Claim> prepared = new ArrayList<>();
        for (int order = 0; order < claims.size(); order++) {
            JsonNode claim = claims.get(order);
            String text = claim.path("text").asText(null);
            InsightAgentRequest.ClaimSource source = sourceOf(claim, summary);
            if (source == null) {
                continue;
            }
            List<InsightAgentRequest.Evidence> evidence = evidenceOf(claim.path("evidence"), source);
            if (text == null || text.isBlank() || evidence.isEmpty()) {
                continue;
            }
            prepared.add(new InsightAgentRequest.Claim(
                    summary.getId() + "-" + order, text, entryDate,
                    institution.getId(), institutionType, source, evidence));
        }
        return prepared;
    }

    private InsightAgentRequest.ClaimSource sourceOf(JsonNode claim, SummaryResult summary) {
        JsonNode source = claim.path("source");
        if (source.isMissingNode() || source.isNull()) {
            return InsightAgentRequest.ClaimSource.AI;
        }
        try {
            return InsightAgentRequest.ClaimSource.valueOf(source.asText());
        } catch (IllegalArgumentException e) {
            log.warn("unknown claim source, claim dropped summaryId={} source={}", summary.getId(), source);
            return null;
        }
    }

    private List<InsightAgentRequest.Evidence> evidenceOf(JsonNode evidence,
            InsightAgentRequest.ClaimSource source) {
        if (!evidence.isArray()) {
            return List.of();
        }
        boolean entryOptional = source == InsightAgentRequest.ClaimSource.TEACHER;
        List<InsightAgentRequest.Evidence> prepared = new ArrayList<>();
        for (JsonNode one : evidence) {
            JsonNode journalEntryId = one.path("journal_entry_id");
            String quote = one.path("quote").asText(null);
            if (quote == null || quote.isBlank()) {
                continue;
            }
            boolean hasEntry = journalEntryId.isNumber();
            if (!hasEntry && !entryOptional) {
                continue;
            }
            prepared.add(new InsightAgentRequest.Evidence(hasEntry ? journalEntryId.asLong() : null, quote));
        }
        return prepared;
    }

    private static JsonNode readClaims(SummaryResult summary) {
        return JsonColumns.readTree(summary.getClaims(), "summary_result.claims", summary.getId());
    }
}
