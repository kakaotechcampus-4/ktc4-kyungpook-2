package com.itda.backend.service.notify;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.dto.request.notify.NotifyAgentRequest;
import com.itda.backend.global.json.JsonColumns;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.insight.InsightEvidenceReader;
import com.itda.backend.service.insight.InstitutionTypeNames;
import com.itda.backend.service.insight.PrimarySource;
import com.itda.backend.service.sharing.GuardianInsightService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private final InsightResultRepository insightResultRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final OrganizationRepository organizationRepository;
    private final InsightEvidenceReader insightEvidenceReader;

    @Transactional(readOnly = true)
    public Optional<PreparedNotify> prepare(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        List<InsightResult> approved = insightResultRepository
                .findByChildIdAndStatusOrderByIdAsc(childId, InsightStatus.APPROVED).stream()
                .filter(insight -> !insight.getPeriodFrom().isBefore(periodFrom))
                .filter(insight -> !insight.getPeriodTo().isAfter(periodTo))
                .toList();
        if (approved.isEmpty()) {
            return Optional.empty();
        }

        Optional<Child> child = childRepository.findById(childId);
        if (child.isEmpty()) {
            log.info("child not found, nothing to notify childId={}", childId);
            return Optional.empty();
        }

        Map<Long, InsightEvidenceReader.Evidence> evidences = insightEvidenceReader.read(approved);
        Map<Long, Organization> institutions = institutionsOf(approved, evidences);
        List<NotifyAgentRequest.ApprovedInsight> insights = approved.stream()
                .map(insight -> new NotifyAgentRequest.ApprovedInsight(
                        insight.getContent(),
                        primaryNameOf(insight, institutions),
                        GuardianInsightService.periodLabel(insight.getPeriodFrom()),
                        institutionTypesOf(insight, institutions),
                        insight.isCrossInstitution(),
                        evidenceOf(evidences.getOrDefault(insight.getId(),
                                InsightEvidenceReader.Evidence.EMPTY), institutions)))
                .toList();

        long lastInsightId = approved.get(approved.size() - 1).getId();
        NotifyAgentRequest request = new NotifyAgentRequest(
                childId, child.get().getName(), periodFrom.toString(), periodTo.toString(),
                insights, null, otherChildNames(childId, institutions.keySet()));
        return Optional.of(new PreparedNotify(request, insights.size(), lastInsightId));
    }

    private static List<Long> sourceIdsOf(InsightResult insight) {
        return JsonColumns.readLongsAllowingText(insight.getSourceInstitutionIds(),
                "insight_result.source_institution_ids", insight.getId());
    }

    private Map<Long, Organization> institutionsOf(List<InsightResult> approved,
            Map<Long, InsightEvidenceReader.Evidence> evidences) {
        Set<Long> ids = approved.stream()
                .flatMap(insight -> sourceIdsOf(insight).stream())
                .collect(Collectors.toCollection(java.util.HashSet::new));
        evidences.values().forEach(evidence -> ids.addAll(evidence.institutionIds()));
        return organizationRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Organization::getId, organization -> organization));
    }

    private String primaryNameOf(InsightResult insight, Map<Long, Organization> institutions) {
        try {
            Organization primary = PrimarySource.of(sourceIdsOf(insight), institutions);
            return primary == null ? "" : primary.getName();
        } catch (RuntimeException e) {
            log.warn("could not pick the primary institution insightId={}", insight.getId(), e);
            return "";
        }
    }

    private List<NotifyAgentRequest.EvidenceEntry> evidenceOf(InsightEvidenceReader.Evidence evidence,
            Map<Long, Organization> institutions) {
        return evidence.refs().stream()
                .filter(ref -> institutions.containsKey(ref.institutionId()))
                .filter(ref -> !ref.sentences().isEmpty())
                .sorted(Comparator
                        .comparing(InsightEvidenceReader.Ref::date)
                        .thenComparing(ref -> institutions.get(ref.institutionId()).getName()))
                .map(ref -> new NotifyAgentRequest.EvidenceEntry(
                        institutions.get(ref.institutionId()).getName(),
                        ref.date().toString(),
                        ref.lookedUp(),
                        ref.sentences().stream()
                                .map(s -> new NotifyAgentRequest.EvidenceSentence(s.text(), s.role()))
                                .toList()))
                .toList();
    }

    private List<String> institutionTypesOf(InsightResult insight, Map<Long, Organization> institutions) {
        return sourceIdsOf(insight).stream()
                .map(institutions::get)
                .filter(organization -> organization != null)
                .map(organization -> InstitutionTypeNames.of(organization.getType()))
                .distinct()
                .toList();
    }

    private List<String> otherChildNames(Long childId, Set<Long> institutionIds) {
        if (institutionIds.isEmpty()) {
            return List.of();
        }
        Set<Long> others = new TreeSet<>();
        institutionIds.forEach(institutionId -> childOrganizationRepository
                .findByOrganizationIdAndDeletedAtIsNull(institutionId)
                .forEach(link -> others.add(link.getChildId())));
        others.remove(childId);
        if (others.isEmpty()) {
            return List.of();
        }
        return childRepository.findAllById(others).stream()
                .sorted(Comparator.comparing(Child::getId))
                .map(Child::getName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }
}
