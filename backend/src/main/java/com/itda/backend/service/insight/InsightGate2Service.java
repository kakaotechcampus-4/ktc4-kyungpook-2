package com.itda.backend.service.insight;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.Gate2Action;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.insight.Gate2DecisionResponse;
import com.itda.backend.exception.insight.InsightErrorCode;
import com.itda.backend.exception.insight.InsightException;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightGate2Service {

    private final UserService userService;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final InsightResultRepository insightResultRepository;
    private final OrganizationRepository organizationRepository;
    private final ChildGuardianRepository childGuardianRepository;
    private final SharingHistoryRepository sharingHistoryRepository;
    private final ApprovalRepository approvalRepository;
    private final ConsentStateReader consentStateReader;

    @Transactional
    public Gate2DecisionResponse decide(String userId, Long insightId, Gate2Action action) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        InsightResult insight = insightResultRepository.findByIdForUpdate(insightId)
                .orElseThrow(() -> new InsightException(InsightErrorCode.NOT_FOUND, insightId));

        requireStillOurs(insight, organizationId);
        Organization primary = PrimarySource.requireApprover(insight, organizationId, organizationsOf(insight));
        requireDecidable(insight);
        requireLatestRevision(insight);

        ApprovalDecision decision = switch (action) {
            case APPROVE -> {
                childRepository.findByIdForUpdate(insight.getChildId());
                Map<Long, ConsentState> insightConsents = consentStateReader.insightStatesOf(
                        List.of(insight.getChildId())).getOrDefault(insight.getChildId(), Map.of());
                requireMaterialStillConsented(insight, insightConsents);
                yield approve(insight, primary, insightConsents);
            }
            case HOLD -> hold(insight);
        };
        approvalRepository.save(Approval.gate2(insightId, Long.valueOf(userId), decision));
        return new Gate2DecisionResponse(String.valueOf(insightId),
                insight.getStatus().name().toLowerCase(Locale.ROOT), decision.name());
    }

    private void requireStillOurs(InsightResult insight, Long organizationId) {
        if (childRepository.findByIdAndDeletedAtIsNull(insight.getChildId()).isEmpty()
                || !childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(
                        insight.getChildId(), organizationId)) {
            throw new InsightException(InsightErrorCode.NOT_FOUND, insight.getId());
        }
    }

    private Map<Long, Organization> organizationsOf(InsightResult insight) {
        return organizationRepository.findAllById(PrimarySource.sourceIdsOf(insight)).stream()
                .collect(Collectors.toMap(Organization::getId, org -> org));
    }

    private ApprovalDecision approve(InsightResult insight, Organization primary,
            Map<Long, ConsentState> insightConsents) {
        insight.approveGate2();
        List<ChildGuardian> guardians =
                childGuardianRepository.findByChildIdAndDeletedAtIsNullOrderByLinkedAtAsc(insight.getChildId());
        if (guardians.isEmpty()) {
            log.warn("gate2 approved but no guardian linked, nobody can read it insightId={} childId={}",
                    insight.getId(), insight.getChildId());
        }
        sharingHistoryRepository.saveAll(guardians.stream()
                .map(guardian -> SharingHistory.grant(insight.getChildId(), insight.getId(), primary.getId(),
                        ReceiverType.GUARDIAN, guardian.getUserId()))
                .toList());
        sharingHistoryRepository.saveAll(consentedRecipients(insight, primary, insightConsents).stream()
                .map(organizationId -> SharingHistory.grant(insight.getChildId(), insight.getId(),
                        primary.getId(), ReceiverType.ORGANIZATION, organizationId))
                .toList());
        return ApprovalDecision.APPROVED;
    }

    private List<Long> consentedRecipients(InsightResult insight, Organization primary,
            Map<Long, ConsentState> insightConsents) {
        Long childId = insight.getChildId();
        List<Long> enrolled = childOrganizationRepository.findByChildIdAndDeletedAtIsNull(childId).stream()
                .map(ChildOrganization::getOrganizationId)
                .toList();
        return Gate2Recipients.candidatesOf(enrolled, primary.getId()).stream()
                .filter(id -> insightConsents.getOrDefault(id, ConsentState.NOT_GRANTED) == ConsentState.GRANTED)
                .toList();
    }

    private ApprovalDecision hold(InsightResult insight) {
        insight.holdGate2();
        return ApprovalDecision.HOLD;
    }

    private void requireMaterialStillConsented(InsightResult insight, Map<Long, ConsentState> insightConsents) {
        for (Long organizationId : PrimarySource.sourceIdsOf(insight)) {
            if (insightConsents.getOrDefault(organizationId, ConsentState.NOT_GRANTED) != ConsentState.GRANTED) {
                throw new InsightException(InsightErrorCode.MATERIAL_CONSENT_REVOKED,
                        "insightId=" + insight.getId() + " organizationId=" + organizationId);
            }
        }
    }

    private static void requireDecidable(InsightResult insight) {
        if (insight.getStatus() != InsightStatus.GATE2_PENDING) {
            throw new InsightException(InsightErrorCode.ALREADY_REVIEWED, insight.getId());
        }
    }

    private void requireLatestRevision(InsightResult insight) {
        Integer latest = insightResultRepository.findMaxRevision(
                insight.getChildId(), insight.getPeriodFrom(), insight.getPeriodTo());
        if (latest != null && latest > insight.getRevision()) {
            throw new InsightException(InsightErrorCode.SUPERSEDED, insight.getId());
        }
    }
}
