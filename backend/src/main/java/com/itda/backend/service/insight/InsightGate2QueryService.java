package com.itda.backend.service.insight;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.dto.response.insight.Gate2InsightResponse;
import com.itda.backend.dto.response.insight.Gate2InsightResponse.EvidenceRef;
import com.itda.backend.dto.response.insight.Gate2InsightResponse.Target;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.insight.InsightErrorCode;
import com.itda.backend.exception.insight.InsightException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightKey;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.insight.InsightEvidenceReader.Evidence;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InsightGate2QueryService {

    private static final String EVIDENCE_LABEL = "관찰일지 요약";

    private static final boolean NEEDS_REVIEW = true;

    private final UserService userService;
    private final InsightResultRepository insightResultRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final OrganizationRepository organizationRepository;
    private final ConsentStateReader consentStateReader;
    private final InsightEvidenceReader insightEvidenceReader;

    @Transactional(readOnly = true)
    public List<Gate2InsightResponse> getInsights(String userId, String gate2Status) {
        InsightStatus status = toInsightStatus(gate2Status);
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<Long> childIds = rosterOf(organizationId);
        if (childIds.isEmpty()) {
            return List.of();
        }
        return assemble(organizationId, insightResultRepository.findLatestByChildIdInAndStatus(childIds, status), false);
    }

    @Transactional(readOnly = true)
    public int countInsights(Long organizationId, InsightStatus status) {
        List<Long> childIds = rosterOf(organizationId);
        if (childIds.isEmpty()) {
            return 0;
        }
        List<InsightKey> keys = insightResultRepository.findLatestKeysByChildIdInAndStatus(childIds, status);
        if (keys.isEmpty()) {
            return 0;
        }
        Map<Long, List<Long>> sourceIds = new HashMap<>();
        keys.forEach(k -> sourceIds.put(k.id(), PrimarySource.sourceIdsOf(k.id(), k.sourceInstitutionIds())));
        Set<Long> organizationIds = new HashSet<>();
        sourceIds.values().forEach(organizationIds::addAll);
        Judgment judgment = judgmentOf(keys.stream().map(InsightKey::childId).collect(Collectors.toSet()),
                organizationIds);
        int count = 0;
        for (InsightKey key : keys) {
            try {
                authorize(key.id(), key.childId(), sourceIds.get(key.id()), organizationId, judgment);
                count++;
            } catch (InsightException e) {
            }
        }
        return count;
    }

    @Transactional(readOnly = true)
    public Gate2InsightResponse getInsight(String userId, Long insightId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        InsightResult insight = insightResultRepository.findById(insightId)
                .orElseThrow(() -> new InsightException(InsightErrorCode.NOT_FOUND, insightId));
        return assemble(organizationId, List.of(insight), true).get(0);
    }

    private List<Long> rosterOf(Long organizationId) {
        return childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId)
                .stream().map(ChildOrganization::getChildId).distinct().toList();
    }

    private List<Gate2InsightResponse> assemble(Long organizationId, List<InsightResult> insights, boolean strict) {
        if (insights.isEmpty()) {
            return List.of();
        }
        Set<Long> childIds = insights.stream().map(InsightResult::getChildId).collect(Collectors.toSet());
        Map<Long, Evidence> evidences = insightEvidenceReader.read(insights);

        Set<Long> organizationIds = new HashSet<>();
        insights.forEach(i -> organizationIds.addAll(PrimarySource.sourceIdsOf(i)));
        evidences.values().forEach(e -> organizationIds.addAll(e.institutionIds()));
        Judgment judgment = judgmentOf(childIds, organizationIds);

        List<Gate2InsightResponse> responses = new ArrayList<>();
        for (InsightResult insight : insights) {
            Organization primary;
            try {
                primary = authorize(insight.getId(), insight.getChildId(), PrimarySource.sourceIdsOf(insight),
                        organizationId, judgment);
            } catch (InsightException e) {
                if (strict) {
                    throw e;
                }
                continue;
            }
            responses.add(toResponse(insight, primary, judgment.childNames(), judgment.enrolled(),
                    judgment.consents(), evidences, judgment.organizations()));
        }
        return responses;
    }

    private record Judgment(Map<Long, String> childNames, Map<Long, List<Long>> enrolled,
            Map<Long, Map<Long, ConsentState>> consents, Map<Long, Organization> organizations) {
    }

    private Judgment judgmentOf(Set<Long> childIds, Set<Long> organizationIds) {
        Map<Long, String> childNames = childRepository.findAllById(childIds).stream()
                .filter(child -> child.getDeletedAt() == null)
                .collect(Collectors.toMap(Child::getId, Child::getName));
        Map<Long, List<Long>> enrolled = childOrganizationRepository.findOrganizationIdsByChildIdIn(childIds);
        Map<Long, Map<Long, ConsentState>> consents = consentStateReader.insightStatesOf(childIds);
        Set<Long> toRead = new HashSet<>(organizationIds);
        enrolled.values().forEach(toRead::addAll);
        Map<Long, Organization> organizations = organizationRepository.findAllById(toRead).stream()
                .collect(Collectors.toMap(Organization::getId, org -> org));
        return new Judgment(childNames, enrolled, consents, organizations);
    }

    private static Organization authorize(Long insightId, Long childId, List<Long> sourceIds, Long organizationId,
            Judgment judgment) {
        requireStillOurs(insightId, childId, organizationId, judgment.childNames(), judgment.enrolled());
        requireMaterialConsented(insightId, childId, sourceIds, judgment.consents());
        return PrimarySource.requireApprover(insightId, sourceIds, organizationId, judgment.organizations());
    }

    private static void requireMaterialConsented(Long insightId, Long childId, List<Long> sourceIds,
            Map<Long, Map<Long, ConsentState>> consents) {
        Map<Long, ConsentState> ofChild = consents.getOrDefault(childId, Map.of());
        for (Long organizationId : sourceIds) {
            if (ofChild.getOrDefault(organizationId, ConsentState.NOT_GRANTED) != ConsentState.GRANTED) {
                throw new InsightException(InsightErrorCode.NOT_FOUND, insightId);
            }
        }
    }

    private static void requireStillOurs(Long insightId, Long childId, Long organizationId,
            Map<Long, String> liveChildNames, Map<Long, List<Long>> enrolled) {
        if (!liveChildNames.containsKey(childId)
                || !enrolled.getOrDefault(childId, List.of()).contains(organizationId)) {
            throw new InsightException(InsightErrorCode.NOT_FOUND, insightId);
        }
    }

    private Gate2InsightResponse toResponse(InsightResult insight, Organization primary,
            Map<Long, String> childNames, Map<Long, List<Long>> enrolled,
            Map<Long, Map<Long, ConsentState>> consents, Map<Long, Evidence> evidences,
            Map<Long, Organization> organizations) {
        Long childId = insight.getChildId();
        Map<Long, ConsentState> consentOfChild = consents.getOrDefault(childId, Map.of());
        List<Target> targets = Gate2Recipients.candidatesOf(enrolled.getOrDefault(childId, List.of()), primary.getId())
                .stream()
                .map(organizations::get)
                .filter(org -> org != null)
                .map(org -> new Target(InstitutionResponse.from(org),
                        consentOfChild.getOrDefault(org.getId(), ConsentState.NOT_GRANTED)))
                .toList();

        List<EvidenceRef> evidence = evidences.getOrDefault(insight.getId(), Evidence.EMPTY).refs().stream()
                .filter(ref -> organizations.containsKey(ref.institutionId()))
                .map(ref -> new EvidenceRef(String.valueOf(ref.childContextId()), ref.date().toString(),
                        EVIDENCE_LABEL, InstitutionResponse.from(organizations.get(ref.institutionId())),
                        ref.lookedUp()))
                .toList();

        return new Gate2InsightResponse(
                String.valueOf(insight.getId()),
                String.valueOf(childId),
                childNames.get(childId),
                periodLabel(insight.getPeriodFrom()),
                insight.getContent(),
                NEEDS_REVIEW,
                evidences.getOrDefault(insight.getId(), Evidence.EMPTY).teacherWritten(),
                InstitutionResponse.from(primary),
                evidence,
                targets,
                insight.getStatus() == InsightStatus.GATE2_PENDING
                        ? "pending" : insight.getStatus().name().toLowerCase(Locale.ROOT));
    }

    static String periodLabel(LocalDate periodFrom) {
        int weekOfMonth = (periodFrom.getDayOfMonth() - 1) / 7 + 1;
        return "%d년 %d월 %d주차".formatted(periodFrom.getYear(), periodFrom.getMonthValue(), weekOfMonth);
    }

    private static InsightStatus toInsightStatus(String gate2Status) {
        return switch (gate2Status) {
            case "pending" -> InsightStatus.GATE2_PENDING;
            case "approved" -> InsightStatus.APPROVED;
            case "hold" -> InsightStatus.HOLD;
            default -> throw new InsightException(InsightErrorCode.INVALID_REQUEST,
                    "unsupported gate2Status: " + gate2Status);
        };
    }
}
